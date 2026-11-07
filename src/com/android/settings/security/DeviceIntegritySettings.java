/* SPDX-License-Identifier: Apache-2.0 */
package com.android.settings.security;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.settings.SettingsEnums;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.text.InputType;
import android.util.Base64;
import android.widget.EditText;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settings.R;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Primary-user controls for framework-managed device provisioning. */
@SearchIndexable
public class DeviceIntegritySettings extends DashboardFragment {
    private static final int IMPORT_KEYBOX = 701;
    private static final int KEYBOX_LIMIT = 64 * 1024;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private boolean mResumed;
    private boolean mPending;
    private boolean mRefreshing;
    private Bundle mConfig = new Bundle();
    private SwitchPreferenceCompat mMaster;
    private SwitchPreferenceCompat mRkp;
    private AlertDialog mEditor;
    private AlertDialog mAppPicker;
    private final Runnable mPoll = this::refresh;

    @Override public int getMetricsCategory() { return SettingsEnums.SECURITY; }
    @Override protected String getLogTag() { return "DeviceIntegritySettings"; }
    @Override protected int getPreferenceScreenResId() { return R.xml.device_integrity_settings; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mMaster = findPreference("integrity_master");
        mRkp = findPreference("integrity_rkp");
        mMaster.setOnPreferenceChangeListener((p, value) -> {
            Bundle config = new Bundle();
            config.putBoolean("master_enabled", (Boolean) value);
            perform(() -> ActivityManager.getService().setDeviceIntegrityConfig(config));
            return false;
        });
        mRkp.setOnPreferenceChangeListener((p, value) -> {
            perform(() -> ActivityManager.getService().setDuckRkpEnabled((Boolean) value));
            return false;
        });
        bind("integrity_provision", () -> perform(() -> ActivityManager.getService().provisionDuckRkp()));
        bind("integrity_targets", this::chooseApps);
        bind("integrity_pif", () -> edit("pif", R.string.device_integrity_pif,
                R.string.device_integrity_pif_summary));
        bind("integrity_patch", () -> edit("patch", R.string.device_integrity_patch,
                R.string.device_integrity_patch_summary));
        bind("integrity_import", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT_KEYBOX);
        });
        bind("integrity_remove", () -> new AlertDialog.Builder(requireContext())
                .setTitle(R.string.device_integrity_remove)
                .setMessage(R.string.device_integrity_remove_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.device_integrity_remove, (dialog, which) ->
                        perform(() -> ActivityManager.getService().setSpoofTrickyStoreKeyBox("")))
                .show());
        findPreference("integrity_status").setSummary(R.string.device_integrity_loading);
        getPreferenceScreen().setEnabled(false);
    }

    private void bind(String key, Runnable action) {
        findPreference(key).setOnPreferenceClickListener(p -> { action.run(); return true; });
    }

    @Override public void onResume() {
        super.onResume();
        mResumed = true;
        refresh();
    }

    @Override public void onPause() {
        mResumed = false;
        mUi.removeCallbacks(mPoll);
        super.onPause();
    }

    @Override public void onDestroy() {
        if (mEditor != null) mEditor.dismiss();
        if (mAppPicker != null) mAppPicker.dismiss();
        mWorker.shutdown();
        super.onDestroy();
    }

    private void refresh() {
        mUi.removeCallbacks(mPoll);
        if (!mResumed || mPending || mRefreshing) return;
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            getPreferenceScreen().setEnabled(false);
            findPreference("integrity_status").setSummary(R.string.device_integrity_owner_only);
            return;
        }
        mRefreshing = true;
        mWorker.execute(() -> {
            Bundle config = null, state = null;
            Exception error = null;
            try {
                config = ActivityManager.getService().getDeviceIntegrityConfig();
                state = ActivityManager.getService().getDuckRkpState();
            } catch (Exception e) { error = e; }
            final Bundle nextConfig = config, nextState = state;
            final Exception failure = error;
            mUi.post(() -> {
                mRefreshing = false;
                if (!mResumed || !isAdded() || mPending) return;
                if (failure == null && nextConfig != null && nextState != null) {
                    mConfig = nextConfig;
                    render(nextState);
                } else {
                    getPreferenceScreen().setEnabled(false);
                    findPreference("integrity_status").setSummary(R.string.device_integrity_unsupported);
                }
                mUi.postDelayed(mPoll, 2000);
            });
        });
    }

    private void render(Bundle state) {
        getPreferenceScreen().setEnabled(true);
        boolean master = mConfig.getBoolean("master_enabled");
        boolean available = state.getBoolean("available");
        boolean imported = state.getBoolean("imported");
        boolean busy = state.getBoolean("busy");
        mMaster.setChecked(master);
        mRkp.setChecked(state.getBoolean("enabled"));
        mRkp.setEnabled(master && available && !imported && !busy);
        boolean canProvision = master && available && state.getBoolean("enabled") && !imported && !busy;
        long cooldown = state.getLong("retry_after_ms");
        Preference provision = findPreference("integrity_provision");
        provision.setEnabled(canProvision && cooldown == 0);
        provision.setSummary(cooldown > 0 ? getString(R.string.device_integrity_cooldown,
                (cooldown + 999) / 1000) : getString(R.string.device_integrity_provision_summary));
        findPreference("integrity_remove").setEnabled(imported && !busy);
        findPreference("integrity_import").setEnabled(!busy);
        int status = !master ? R.string.device_integrity_disabled
                : imported ? R.string.device_integrity_imported
                : !available ? R.string.device_integrity_unsupported
                : state.getBoolean("active") ? R.string.device_integrity_ready
                : state.getBoolean("has_keybox") ? R.string.device_integrity_saved
                : R.string.device_integrity_idle;
        if (busy) status = switch (state.getString("stage", "")) {
            case "fetching" -> R.string.device_integrity_fetching;
            case "signing" -> R.string.device_integrity_signing;
            case "saving" -> R.string.device_integrity_saving;
            default -> R.string.device_integrity_hardware;
        };
        String error = master ? state.getString("error", "") : "";
        findPreference("integrity_status").setSummary(error.isEmpty() ? getString(status)
                : getString(R.string.device_integrity_error_detail, getString(errorMessage(error)),
                        error, state.getInt("detail")));
        findPreference("integrity_expiry").setSummary(date(state.getLong("expires_at")));
        findPreference("integrity_renewal").setSummary(!master || !state.getBoolean("enabled")
                ? getString(R.string.device_integrity_disabled)
                : state.getBoolean("automatic_paused") ? getString(R.string.device_integrity_paused)
                : state.getBoolean("waiting_for_network") ? getString(R.string.device_integrity_waiting)
                : date(state.getLong("next_automatic_at") > 0 ? state.getLong("next_automatic_at")
                        : state.getLong("renew_at")));
        long rules = mConfig.getString("targets", "").lines()
                .filter(line -> !line.isBlank() && !line.trim().startsWith("#")).count();
        findPreference("integrity_targets").setSummary(getString(R.string.device_integrity_targets_count, rules));
        findPreference("integrity_pif").setSummary(mConfig.getString("pif", "").isBlank()
                ? R.string.device_integrity_unconfigured : R.string.device_integrity_configured);
    }

    private String date(long timestamp) {
        return timestamp <= 0 ? getString(R.string.device_integrity_none)
                : DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(timestamp));
    }

    private int errorMessage(String code) {
        if (code.contains("timeout")) return R.string.device_integrity_error_timeout;
        if (code.equals("network_error") || code.equals("http_error")) return R.string.device_integrity_error_network;
        if (code.startsWith("hal_") || code.startsWith("native_")) return R.string.device_integrity_error_hardware;
        if (code.contains("response") || code.contains("key_invalid")) return R.string.device_integrity_error_certificate;
        if (code.contains("save") || code.contains("settings")) return R.string.device_integrity_error_storage;
        return R.string.device_integrity_error_generic;
    }

    private interface Action { void run() throws Exception; }

    private void perform(Action action) {
        perform(action, null);
    }

    private void perform(Action action, Runnable onSuccess) {
        if (mPending) return;
        mPending = true;
        getPreferenceScreen().setEnabled(false);
        mWorker.execute(() -> {
            Exception error = null;
            try { action.run(); } catch (Exception e) { error = e; }
            final Exception failure = error;
            mUi.post(() -> {
                mPending = false;
                if (!isAdded()) return;
                if (failure != null) Toast.makeText(requireContext(),
                        getString(R.string.device_integrity_action_failed, failure.getMessage()),
                        Toast.LENGTH_LONG).show();
                if (failure == null && onSuccess != null) onSuccess.run();
                refresh();
            });
        });
    }

    private void chooseApps() {
        var packageManager = requireContext().getPackageManager();
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.Map<String, String> packages = new java.util.HashMap<>();
        perform(() -> {
            for (var app : packageManager.getInstalledApplications(0)) {
                String label = app.loadLabel(packageManager) + "\n" + app.packageName;
                labels.add(label);
                packages.put(label, app.packageName);
            }
            labels.sort(String.CASE_INSENSITIVE_ORDER);
        }, () -> {
            var layout = new android.widget.LinearLayout(requireContext());
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            EditText search = new EditText(requireContext());
            search.setSingleLine(true);
            search.setHint(R.string.device_integrity_search_apps);
            search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            int padding = (int) (20 * getResources().getDisplayMetrics().density);
            search.setPadding(padding, padding, padding, padding);
            layout.addView(search);
            var list = new android.widget.ListView(requireContext());
            var adapter = new android.widget.ArrayAdapter<String>(requireContext(),
                    android.R.layout.simple_list_item_1, labels);
            list.setAdapter(adapter);
            layout.addView(list, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (350 * getResources().getDisplayMetrics().density)));
            search.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    adapter.getFilter().filter(text);
                }
                @Override public void afterTextChanged(android.text.Editable text) {}
            });
            list.setOnItemClickListener((parent, view, position, id) ->
                    chooseMode(packages.get(adapter.getItem(position))));
            mAppPicker = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.device_integrity_targets).setView(layout)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.device_integrity_edit_rules, (dialog, which) ->
                            edit("targets", R.string.device_integrity_targets,
                                    R.string.device_integrity_targets_summary)).create();
            mAppPicker.show();
        });
    }

    private void chooseMode(String packageName) {
        if (packageName == null || mPending) return;
        String[] suffixes = {null, "", "!", "?"};
        int selected = 0;
        for (String raw : mConfig.getString("targets", "").split("\n")) {
            String line = raw.trim();
            for (int i = 1; i < suffixes.length; i++) {
                if (line.equals(packageName + suffixes[i])) selected = i;
            }
            if (line.equals(packageName + "-")) selected = 0;
        }
        new AlertDialog.Builder(requireContext()).setTitle(packageName)
                .setSingleChoiceItems(R.array.device_integrity_app_modes, selected, (dialog, mode) -> {
                    StringBuilder rules = new StringBuilder();
                    for (String raw : mConfig.getString("targets", "").split("\n")) {
                        String line = raw.trim();
                        if (line.equals(packageName) || line.equals(packageName + "!")
                                || line.equals(packageName + "?") || line.equals(packageName + "-")) continue;
                        rules.append(raw).append('\n');
                    }
                    // An explicit skip prevents a future default list from targeting this app.
                    rules.append(packageName).append(mode == 0 ? "-" : suffixes[mode]);
                    Bundle config = new Bundle();
                    config.putString("targets", rules.toString());
                    perform(() -> ActivityManager.getService().setDeviceIntegrityConfig(config),
                            () -> mConfig.putString("targets", rules.toString()));
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void edit(String key, int title, int description) {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(6);
        input.setMaxLines(14);
        input.setText(mConfig.getString(key, ""));
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding, padding, padding);
        mEditor = new AlertDialog.Builder(requireContext()).setTitle(title).setMessage(description)
                .setView(input).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.device_integrity_save, null).create();
        mEditor.setOnShowListener(ignored -> mEditor.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    Bundle config = new Bundle();
                    config.putString(key, input.getText().toString());
                    perform(() -> ActivityManager.getService().setDeviceIntegrityConfig(config),
                            () -> mEditor.dismiss());
                }));
        mEditor.show();
    }

    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT_KEYBOX || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        var resolver = requireContext().getContentResolver();
        var uri = data.getData();
        perform(() -> {
            byte[] bytes;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("Cannot open document");
                bytes = input.readNBytes(KEYBOX_LIMIT + 1);
            }
            if (bytes.length == 0 || bytes.length > KEYBOX_LIMIT)
                throw new IllegalArgumentException("Keybox must be between 1 byte and 64 KiB");
            String xml = new String(bytes, StandardCharsets.UTF_8);
            if (!xml.contains("<AndroidAttestation>"))
                throw new IllegalArgumentException("Choose an Android attestation keybox XML");
            ActivityManager.getService().setSpoofTrickyStoreKeyBox(
                    Base64.encodeToString(bytes, Base64.NO_WRAP));
        });
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.device_integrity_settings) {
                @Override protected boolean isPageSearchEnabled(android.content.Context context) {
                    return UserHandle.myUserId() == UserHandle.USER_SYSTEM;
                }
            };
}
