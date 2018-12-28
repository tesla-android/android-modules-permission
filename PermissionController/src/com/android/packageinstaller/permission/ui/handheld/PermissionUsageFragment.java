/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.permission.ui.handheld;

import android.app.ActionBar;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissionUsage;
import com.android.packageinstaller.permission.model.PermissionApps;
import com.android.packageinstaller.permission.model.PermissionApps.PermissionApp;
import com.android.packageinstaller.permission.model.PermissionGroup;
import com.android.packageinstaller.permission.model.PermissionGroups;
import com.android.packageinstaller.permission.utils.Utils;
import com.android.permissioncontroller.R;
import com.android.settingslib.HelpUtils;
import com.android.settingslib.widget.settingsspinner.SettingsSpinnerAdapter;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Show the usage of all apps of all permission groups.
 *
 * <p>Shows a filterable list of app usage of permission groups, each of which links to
 * AppPermissionsFragment.
 */
public class PermissionUsageFragment extends PermissionsFrameFragment implements
        PermissionGroups.PermissionsGroupsChangeCallback, OnItemSelectedListener {
    private static final String LOG_TAG = "PermissionUsageFragment";

    private static final String KEY_SHOW_SYSTEM_PREFS = "_show_system";
    private static final String SHOW_SYSTEM_KEY = PermissionUsageFragment.class.getName()
            + KEY_SHOW_SYSTEM_PREFS;
    private static final String KEY_SPINNER_PERMS_INDEX = "_time_index";
    private static final String SPINNER_PERMS_INDEX_KEY = PermissionUsageFragment.class.getName()
            + KEY_SPINNER_PERMS_INDEX;
    private static final String KEY_SPINNER_TIME_INDEX = "_time_index";
    private static final String SPINNER_TIME_INDEX_KEY = PermissionUsageFragment.class.getName()
            + KEY_SPINNER_TIME_INDEX;

    private PermissionGroups mPermissionGroups;

    private Collator mCollator;
    private ArraySet<String> mLauncherPkgs;

    private boolean mShowSystem;
    private boolean mHasSystemApps;
    private MenuItem mShowSystemMenu;
    private MenuItem mHideSystemMenu;

    private Spinner mFilterSpinnerPermissions;
    private FilterSpinnerAdapter<PermissionFilterItem> mFilterAdapterPermissions;
    private Spinner mFilterSpinnerTime;
    private FilterSpinnerAdapter<TimeFilterItem> mFilterAdapterTime;

    /**
     * Only used to restore permission spinner state after onCreate. Once the first list of groups
     * is reported, this becomes invalid.
     */
    private int mSavedPermsSpinnerIndex;

    /**
     * Only used to restore time spinner state after onCreate. Once the list of times is reported,
     * this becomes invalid.
     */
    private int mSavedTimeSpinnerIndex;

    /**
     * @return A new fragment
     */
    public static @NonNull PermissionUsageFragment newInstance(@Nullable String permissionName) {
        PermissionUsageFragment fragment = new PermissionUsageFragment();
        Bundle arguments = new Bundle();
        if (permissionName != null) {
            arguments.putString(Intent.EXTRA_PERMISSION_NAME, permissionName);
        }
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onStart() {
        super.onStart();
        getActivity().setTitle(R.string.permission_usage_title);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mShowSystem = savedInstanceState.getBoolean(SHOW_SYSTEM_KEY);
            mSavedPermsSpinnerIndex = savedInstanceState.getInt(SPINNER_PERMS_INDEX_KEY);
            mSavedTimeSpinnerIndex = savedInstanceState.getInt(SPINNER_TIME_INDEX_KEY);
        } else {
            mSavedPermsSpinnerIndex = 0;
            mSavedTimeSpinnerIndex = 0;
        }

        setLoading(true, false);
        setHasOptionsMenu(true);
        ActionBar ab = getActivity().getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }

        Context context = getPreferenceManager().getContext();
        mCollator = Collator.getInstance(
                context.getResources().getConfiguration().getLocales().get(0));
        mLauncherPkgs = Utils.getLauncherPackages(context);
        mPermissionGroups = new PermissionGroups(context, getActivity().getLoaderManager(), this,
                true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        // Setup filter spinners.
        ViewGroup root = (ViewGroup) super.onCreateView(inflater, container, savedInstanceState);
        View header = inflater.inflate(R.layout.permission_usage_filter_spinners, root, false);
        getPreferencesContainer().addView(header, 0);

        mFilterSpinnerPermissions = header.requireViewById(R.id.filter_spinner_permissions);
        mFilterAdapterPermissions = new FilterSpinnerAdapter<>(getContext());
        mFilterSpinnerPermissions.setAdapter(mFilterAdapterPermissions);
        mFilterSpinnerPermissions.setOnItemSelectedListener(this);

        mFilterSpinnerTime = header.requireViewById(R.id.filter_spinner_time);
        mFilterAdapterTime = new FilterSpinnerAdapter<>(getContext());
        mFilterSpinnerTime.setAdapter(mFilterAdapterTime);
        mFilterSpinnerTime.setOnItemSelectedListener(this);

        // Add time spinner entries.  We can't add the permissions spinner entries yet since we
        // first have to load the permission groups.
        Context context = getPreferenceManager().getContext();
        mFilterAdapterTime.addFilter(new TimeFilterItem(Long.MAX_VALUE,
                context.getString(R.string.permission_usage_any_time)));
        mFilterAdapterTime.addFilter(new TimeFilterItem(60 * 60 * 24 * 7,
                context.getString(R.string.permission_usage_last_7_days)));
        mFilterAdapterTime.addFilter(new TimeFilterItem(60 * 60 * 24,
                context.getString(R.string.permission_usage_last_day)));
        mFilterAdapterTime.addFilter(new TimeFilterItem(60 * 60,
                context.getString(R.string.permission_usage_last_hour)));
        mFilterAdapterTime.addFilter(new TimeFilterItem(60 * 15,
                context.getString(R.string.permission_usage_last_15_minutes)));
        mFilterSpinnerTime.setSelection(mSavedTimeSpinnerIndex);

        return root;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        addPreferences();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SHOW_SYSTEM_KEY, mShowSystem);
        outState.putInt(SPINNER_PERMS_INDEX_KEY,
                mFilterSpinnerPermissions.getSelectedItemPosition());
        outState.putInt(SPINNER_TIME_INDEX_KEY, mFilterSpinnerTime.getSelectedItemPosition());
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        if (mHasSystemApps) {
            mShowSystemMenu = menu.add(Menu.NONE, MENU_SHOW_SYSTEM, Menu.NONE,
                    R.string.menu_show_system);
            mHideSystemMenu = menu.add(Menu.NONE, MENU_HIDE_SYSTEM, Menu.NONE,
                    R.string.menu_hide_system);
            updateMenu();
        }
        HelpUtils.prepareHelpMenuItem(getActivity(), menu, R.string.help_permission_usage,
                getClass().getName());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                getActivity().finish();
                return true;
            case MENU_SHOW_SYSTEM:
            case MENU_HIDE_SYSTEM:
                mShowSystem = item.getItemId() == MENU_SHOW_SYSTEM;
                addPreferences();
                updateMenu();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateMenu() {
        mShowSystemMenu.setVisible(!mShowSystem);
        mHideSystemMenu.setVisible(mShowSystem);
    }

    @Override
    public void onPermissionGroupsChanged() {
        if (mPermissionGroups.getGroups().isEmpty()) {
            return;
        }
        createPermissionSpinnerEntries();
        addPreferences();
        setLoading(false, true);
    }

    @Override
    public int getEmptyViewString() {
        return R.string.no_permission_usages;
    }

    private void addPreferences() {
        if (getActivity() == null) {
            return;
        }

        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            Context context = getPreferenceManager().getContext();
            screen = getPreferenceManager().createPreferenceScreen(context);
            setPreferenceScreen(screen);
        }
        screen.removeAll();

        // Get the current values of the filters from the spinners.
        String permissionGroupFilter = null;
        int pos = mFilterSpinnerPermissions.getSelectedItemPosition();
        if (pos != AdapterView.INVALID_POSITION) {
            permissionGroupFilter = mFilterAdapterPermissions.getFilter(pos).getGroupLabel();
        }
        long timeFilter = Long.MAX_VALUE;
        pos = mFilterSpinnerTime.getSelectedItemPosition();
        if (pos != AdapterView.INVALID_POSITION) {
            timeFilter = mFilterAdapterTime.getFilter(pos).getTime();
        }

        // Find the permission usages we want to add.
        mHasSystemApps = false;
        boolean menuOptionsInvalided = false;
        Context context = getPreferenceManager().getContext();
        List<AppPermissionUsage> appPermissionUsages = new ArrayList<>();
        List<PermissionGroup> groups = mPermissionGroups.getGroups();
        ArrayMap<AppPermissionGroup, PermissionApp> groupToApp = new ArrayMap<>();
        ArrayMap<String, List<AppPermissionUsage>> appToUsages = new ArrayMap<>();
        ArrayMap<String, ArraySet<AppPermissionGroup>> appToGroups = new ArrayMap<>();
        int numGroups = groups.size();
        for (int groupNum = 0; groupNum < numGroups; groupNum++) {
            PermissionGroup permissionGroup = groups.get(groupNum);
            // Filter out third party permissions
            if (!permissionGroup.getDeclaringPackage().equals(ManagePermissionsFragment.OS_PKG)) {
                continue;
            }
            // Implement group filter.
            if (permissionGroupFilter != null
                    && !permissionGroup.getLabel().equals(permissionGroupFilter)) {
                continue;
            }
            // Ignore {READ,WRITE}_EXTERNAL_STORAGE since they're going away.
            if (permissionGroup.getLabel().equals("Storage")) {
                continue;
            }
            PermissionApps permApps = permissionGroup.getPermissionApps();
            List<PermissionApp> permissionApps = permApps.getApps();
            int numApps = permissionApps.size();
            for (int appNum = 0; appNum < numApps; appNum++) {
                PermissionApp permApp = permissionApps.get(appNum);
                AppPermissionGroup group = permApp.getPermissionGroup();
                if (!Utils.shouldShowPermission(context, group)) {
                    continue;
                }
                List<AppPermissionUsage> groupUsages = group.getAppPermissionUsage();
                int numUsages = groupUsages.size();
                for (int usageNum = 0; usageNum < numUsages; usageNum++) {
                    AppPermissionUsage usage = groupUsages.get(usageNum);
                    if (usage.getTime() == 0) {
                        continue;
                    }
                    // Implement time filter.
                    if ((System.currentTimeMillis() - usage.getTime()) / 1000 > timeFilter) {
                        continue;
                    }

                    boolean isSystemApp = Utils.isSystem(permApp, mLauncherPkgs);
                    if (isSystemApp && !menuOptionsInvalided) {
                        mHasSystemApps = true;
                        getActivity().invalidateOptionsMenu();
                        menuOptionsInvalided = true;
                    }

                    if (!isSystemApp || mShowSystem) {
                        appPermissionUsages.add(usage);
                        groupToApp.put(group, permApp);
                        if (!appToUsages.containsKey(permApp.getKey())) {
                            appToUsages.put(permApp.getKey(), new ArrayList<>());
                        }
                        appToUsages.get(permApp.getKey()).add(usage);
                        if (!appToGroups.containsKey(permApp.getKey())) {
                            appToGroups.put(permApp.getKey(), new ArraySet<>());
                        }
                        appToGroups.get(permApp.getKey()).add(group);
                    }
                }
            }
        }

        // Sort the apps, first by number of permissions then by last access time.
        List<String> apps = new ArrayList<>(appToUsages.keySet());
        apps.sort((x, y) -> {
            int groupDiff = appToGroups.get(y).size() - appToGroups.get(x).size();
            if (groupDiff != 0) {
                return groupDiff;
            }
            return compareAccessTime(appToUsages.get(x).get(0), appToUsages.get(y).get(0));
        });

        // Add the preferences.
        Set<String> addedEntries = new ArraySet<>();
        int numApps = apps.size();
        for (int appNum = 0; appNum < numApps; appNum++) {
            String appKey = apps.get(appNum);
            List<AppPermissionGroup> appGroups = new ArrayList<>(appToGroups.get(appKey));
            appGroups.sort((x, y) -> compareAccessTime(x.getAppPermissionUsage().get(0),
                    y.getAppPermissionUsage().get(0)));

            PreferenceGroup parent = screen;
            if (appGroups.size() > 1) {
                // Add a "parent" entry for the app that will expand to the individual entries.

                List<Integer> groupIcons = new ArrayList<>(appGroups.size());
                numGroups = appGroups.size();
                for (int groupNum = 0; groupNum < numGroups; groupNum++) {
                    groupIcons.add(appGroups.get(groupNum).getIconResId());
                }

                PermissionApp app = groupToApp.get(appGroups.get(0));
                parent = new ExpandablePreferenceGroup(context, groupIcons);
                parent.setTitle(app.getLabel());
                parent.setIcon(app.getIcon());
                screen.addPreference(parent);
            }

            numGroups = appGroups.size();
            for (int groupNum = 0; groupNum < numGroups; groupNum++) {
                AppPermissionGroup group = appGroups.get(groupNum);
                AppPermissionUsage usage = group.getAppPermissionUsage().get(0);
                // Filter out entries we've seen before.
                if (!addedEntries.add(appKey + "," + usage.getPermissionGroupName())) {
                    continue;
                }
                PermissionApp permApp = groupToApp.get(group);
                PermissionControlPreference pref = new PermissionControlPreference(context, group);
                pref.setTitle(permApp.getLabel());
                long timeDiff = System.currentTimeMillis() - usage.getTime();
                String timeDiffStr = Utils.getTimeDiffStr(context, timeDiff);
                pref.setSummary(context.getString(R.string.permission_usage_summary,
                        usage.getPermissionGroupLabel(), timeDiffStr));
                if (appGroups.size() == 1) {
                    pref.setIcon(permApp.getIcon());
                }
                pref.setSummaryIcons(Collections.singletonList(group.getIconResId()));
                pref.setKey(usage.getPackageName() + "," + usage.getPermissionGroupName());
                pref.useSmallerIcon();
                parent.addPreference(pref);
            }
        }
    }

    /**
     * Compare two AppPermissionUsages by their access time.
     *
     * Can be used as a {@link java.util.Comparator}.
     *
     * @param x an AppPermissionUsage.
     * @param y an AppPermissionUsage.
     *
     * @return see {@link java.util.Comparator#compare(Object, Object)}.
     */
    private static int compareAccessTime(@NonNull AppPermissionUsage x,
            @NonNull AppPermissionUsage y) {
        long lastXAccess = x.getTime();
        long lastYAccess = y.getTime();

        if (lastXAccess > lastYAccess) {
            return -1;
        } else if (lastYAccess > lastXAccess) {
            return 1;
        } else {
            return 0;
        }
    }

    private void createPermissionSpinnerEntries() {
        Context context = getPreferenceManager().getContext();
        String permName = getArguments().getString(Intent.EXTRA_PERMISSION_NAME);
        String groupName = Utils.getGroupOfPlatformPermission(permName);
        if (permName != null && groupName == null) {
            Log.w(LOG_TAG, "Invalid platform permission: " + permName);
        }

        // Remember the selected item so we can restore it.
        int selectedPosition = mFilterSpinnerPermissions.getSelectedItemPosition();
        CharSequence selectedLabel = null;
        if (selectedPosition != -1) {
            selectedLabel = mFilterAdapterPermissions.getItem(selectedPosition);
        }

        // Get the permission labels.
        List<PermissionGroup> filterGroups = new ArrayList<>();
        List<PermissionGroup> groups = mPermissionGroups.getGroups();
        for (int i = 0, numGroups = groups.size(); i < numGroups; i++) {
            PermissionGroup permissionGroup = groups.get(i);
            if (permissionGroup.getDeclaringPackage().equals(ManagePermissionsFragment.OS_PKG)) {
                filterGroups.add(permissionGroup);
            }
        }
        filterGroups.sort(
                (x, y) -> mCollator.compare(x.getLabel().toString(), y.getLabel().toString()));

        // Create the spinner entries.
        mFilterAdapterPermissions.clear();
        mFilterAdapterPermissions.addFilter(new PermissionFilterItem(null,
                context.getString(R.string.permission_usage_any_permission)));
        for (int i = 0, numGroups = filterGroups.size(); i < numGroups; i++) {
            PermissionGroup group = filterGroups.get(i);
            mFilterAdapterPermissions.addFilter(new PermissionFilterItem(group,
                    group.getLabel().toString()));

            // Use the permission group passed as an argument, if applicable.
            if (group.getName().equals(groupName) && selectedPosition == -1) {
                selectedLabel = group.getLabel();
                selectedPosition = mFilterAdapterPermissions.getCount() - 1;
            }
        }

        // Restore the previously-selected item.
        if (selectedPosition == -1) {
            // Nothing was selected, so use the saved value.
            selectedPosition = mSavedPermsSpinnerIndex;
        } else if (!mFilterAdapterPermissions.getFilter(selectedPosition).getLabel().equals(
                selectedLabel)) {
            // The previously-selected value no longer exists, so use the default "show all".
            selectedPosition = 0;
        }
        mFilterSpinnerPermissions.setSelection(selectedPosition);
    }

    /**
     * An adapter that stores the entries in a filter spinner.
     * @param <T> The type of the entries in the filter spinner.
     */
    private static class FilterSpinnerAdapter<T extends FilterItem> extends
            SettingsSpinnerAdapter<CharSequence> {
        private final ArrayList<T> mFilterOptions = new ArrayList<>();

        FilterSpinnerAdapter(@NonNull Context context) {
            super(context);
        }

        public void addFilter(@NonNull T filter) {
            mFilterOptions.add(filter);
            notifyDataSetChanged();
        }

        public T getFilter(int position) {
            return mFilterOptions.get(position);
        }

        @Override
        public int getCount() {
            return mFilterOptions.size();
        }

        @Override
        public CharSequence getItem(int position) {
            return mFilterOptions.get(position).getLabel();
        }

        @Override
        public void clear() {
            mFilterOptions.clear();
            super.clear();
        }
    }

    /**
     * An interface to represent items that we can use as filters.
     */
    private interface FilterItem {
        @NonNull String getLabel();
    }

    /**
     * A filter item representing a permission group (or all groups if the given group is null).
     */
    private static class PermissionFilterItem implements FilterItem {
        private final @NonNull PermissionGroup mGroup;
        private final @NonNull String mLabel;

        PermissionFilterItem(PermissionGroup group, @NonNull String label) {
            mGroup = group;
            mLabel = label;
        }

        public String getGroupLabel() {
            return (mGroup == null ? null : mGroup.getLabel().toString());
        }

        public @NonNull String getLabel() {
            return mLabel;
        }
    }

    /**
     * A filter item representing a given time, e.g., "in the last hour".
     */
    private static class TimeFilterItem implements FilterItem {
        private final long mTime;
        private final @NonNull String mLabel;

        TimeFilterItem(long time, @NonNull String label) {
            mTime = time;
            mLabel = label;
        }

        public long getTime() {
            return mTime;
        }

        public @NonNull String getLabel() {
            return mLabel;
        }
    }
}
