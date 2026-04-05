package fr.neamar.kiss.forwarder;

import static android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL;
import static android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandle;
import android.text.TextUtils;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.PickAppWidgetActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.ui.WidgetGridLayout;
import fr.neamar.kiss.ui.WidgetHost;
import fr.neamar.kiss.ui.WidgetView;
import fr.neamar.kiss.utils.DrawableUtils;
import fr.neamar.kiss.utils.Log;

class Widgets extends Forwarder implements WidgetView.OnWidgetInteractionListener {
    private static final String TAG = Widgets.class.getSimpleName();
    private static final int REQUEST_APPWIDGET_CONFIGURED = 5;
    private static final int REQUEST_APPWIDGET_RECONFIGURED = 13;

    private static final int APPWIDGET_HOST_ID = 442;

    private static final String WIDGET_PREF_KEY = "widgets-conf";

    /** Default widget height in dp when minHeight is not specified */
    private static final int DEFAULT_WIDGET_HEIGHT_DP = 100;
    /** Default widget width: use MATCH_PARENT sentinel (-1) */
    private static final int DEFAULT_WIDGET_WIDTH = ViewGroup.LayoutParams.MATCH_PARENT;

    /**
     * Widgets fields
     */
    private AppWidgetManager mAppWidgetManager;
    private AppWidgetHost mAppWidgetHost;

    /**
     * View widgets are added to (WidgetGridLayout for smart grid positioning)
     */
    private WidgetGridLayout widgetArea;
    private ActivityResultLauncher<Intent> requestAppWidgetPicked;
    private ActivityResultLauncher<Intent> requestAppWidgetBound;

    Widgets(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onCreate() {
        mAppWidgetManager = AppWidgetManager.getInstance(mainActivity);
        mAppWidgetHost = new WidgetHost(mainActivity, APPWIDGET_HOST_ID, this::onAppWidgetRemoved);
        widgetArea = mainActivity.findViewById(R.id.widgetLayout);

        requestAppWidgetPicked = mainActivity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), activityResult -> appWidgetPicked(activityResult.getResultCode(), activityResult.getData()));
        requestAppWidgetBound = mainActivity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), activityResult -> appWidgetBound(activityResult.getResultCode(), activityResult.getData()));

        restoreWidgets();
    }

    private void onAppWidgetRemoved() {
        restoreWidgets();
        serializeState();
    }

    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_APPWIDGET_CONFIGURED && resultCode == Activity.RESULT_CANCELED) {
            removeWidget(data);
        }
    }

    private void appWidgetPicked(int resultCode, Intent data) {
        switch (resultCode) {
            case Activity.RESULT_OK:
                if (data != null) {
                    if (!data.getBooleanExtra(PickAppWidgetActivity.EXTRA_WIDGET_BIND_ALLOWED, false)) {
                        Log.w(TAG, "Widget bind not allowed");
                        requestBindWidget(data);
                        break;
                    }
                    addAppWidget(data);
                } else {
                    Log.i(TAG, "Widget picker failed");
                }
                break;
            case Activity.RESULT_CANCELED:
                removeWidget(data);
                break;
        }
    }

    private void appWidgetBound(int resultCode, Intent data) {
        switch (resultCode) {
            case Activity.RESULT_OK:
                if (data != null) {
                    addAppWidget(data);
                } else {
                    Log.i(TAG, "Widget bind failed");
                }
                break;
            case Activity.RESULT_CANCELED:
                removeWidget(data);
                break;
        }
    }

    private void removeWidget(Intent data) {
        if (data != null) {
            int appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                List<View> viewsToRemove = new ArrayList<>();
                for (int i = 0; i < widgetArea.getChildCount(); i++) {
                    AppWidgetHostView view = (AppWidgetHostView) widgetArea.getChildAt(i);
                    if (view.getAppWidgetId() == appWidgetId) {
                        viewsToRemove.add(view);
                    }
                }
                for (View viewToRemove : viewsToRemove) {
                    widgetArea.removeView(viewToRemove);
                }
                mAppWidgetHost.deleteAppWidgetId(appWidgetId);
                serializeState();
            }
        }
    }

    boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.add_widget) {
            int appWidgetId = mAppWidgetHost.allocateAppWidgetId();
            Intent pickIntent = new Intent(mainActivity, PickAppWidgetActivity.class);
            pickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            requestAppWidgetPicked.launch(pickIntent);
            return true;
        }

        return false;
    }

    void onCreateContextMenu(ContextMenu menu) {
        if (!prefs.getBoolean("history-hide", false)) {
            menu.findItem(R.id.add_widget).setVisible(false);
        }
    }

    void onDataSetChanged() {
        if (widgetArea.getChildCount() > 0 && mainActivity.adapter.isEmpty()) {
            mainActivity.emptyListView.setVisibility(View.GONE);
        }
    }

    // -------------------------------------------------------------------------
    // Serialization: format "<id>,<cx>,<cy>,<sx>,<sy>" separated by ";"
    // -------------------------------------------------------------------------

    private void serializeState() {
        List<String> builder = new ArrayList<>(widgetArea.getChildCount());
        for (int i = 0; i < widgetArea.getChildCount(); i++) {
            AppWidgetHostView view = (AppWidgetHostView) widgetArea.getChildAt(i);
            int appWidgetId = view.getAppWidgetId();
            AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
            if (appWidgetInfo != null) {
                WidgetGridLayout.LayoutParams lp = (WidgetGridLayout.LayoutParams) view.getLayoutParams();
                builder.add(appWidgetId + "," + lp.cellX + "," + lp.cellY + "," + lp.spanX + "," + lp.spanY);
            } else {
                Log.w(TAG, "Unable to retrieve widget by id " + appWidgetId);
            }
        }

        String pref = TextUtils.join(";", builder);
        prefs.edit().putString(WIDGET_PREF_KEY, pref).apply();
    }

    /**
     * Display all widgets based on state
     */
    private void restoreWidgets() {
        if (!prefs.getBoolean("history-hide", false)) {
            return;
        }

        mainActivity.emptyListView.setVisibility(View.GONE);
        widgetArea.removeAllViews();
        String widgetsConfString = prefs.getString(WIDGET_PREF_KEY, "");
        String[] widgetsConf = widgetsConfString.split(";");
        Set<Integer> idsUsed = new HashSet<>();

        for (String widgetConf : widgetsConf) {
            if (widgetConf.isEmpty()) {
                continue;
            }
            String[] conf = widgetConf.split(",");
            if (conf.length < 5) {
                // Legacy format (id-lineSize) or invalid – skip
                Log.w(TAG, "Skipping invalid/legacy widget config: " + widgetConf);
                continue;
            }
            try {
                int id = Integer.parseInt(conf[0]);
                int cx = Integer.parseInt(conf[1]);
                int cy = Integer.parseInt(conf[2]);
                int sx = Integer.parseInt(conf[3]);
                int sy = Integer.parseInt(conf[4]);
                idsUsed.add(id);
                addWidget(id, cx, cy, sx, sy);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Failed to parse widget config: " + widgetConf, e);
            }
        }

        // Kill zombie widgets
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            int[] hostWidgetIds = mAppWidgetHost.getAppWidgetIds();
            for (int hostWidgetId : hostWidgetIds) {
                if (!idsUsed.contains(hostWidgetId)) {
                    mAppWidgetHost.deleteAppWidgetId(hostWidgetId);
                }
            }
        }

        mAppWidgetHost.startListening();
    }

    /**
     * Retrieve a WidgetView for the specified widget id, apply grid layout, add context menu.
     *
     * @param appWidgetId id of widget to add
     * @param cx          cell x
     * @param cy          cell y
     * @param sx          span x
     * @param sy          span y
     */
    private void addWidget(int appWidgetId, int cx, int cy, int sx, int sy) {
        AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
        if (appWidgetInfo == null) {
            Log.i(TAG, "Unable to retrieve widget by id " + appWidgetId);
            return;
        }

        WidgetView hostView = (WidgetView) mAppWidgetHost.createView(mainActivity.getApplicationContext(), appWidgetId, appWidgetInfo);
        hostView.setAppWidget(appWidgetId, appWidgetInfo);
        hostView.setOnWidgetInteractionListener(this);

        WidgetGridLayout.LayoutParams lp = new WidgetGridLayout.LayoutParams(cx, cy, sx, sy);
        hostView.setLayoutParams(lp);

        hostView.setLongClickable(true);
        hostView.setOnLongClickListener(v -> {
            WidgetView widgetView = (WidgetView) v;
            AppWidgetProviderInfo currentInfo = mAppWidgetManager.getAppWidgetInfo(widgetView.getAppWidgetId());

            ArrayAdapter<ListPopup.Item> popupMenuAdapter = new ArrayAdapter<>(mainActivity, R.layout.popup_list_item);
            buildPopupMenu(mainActivity, popupMenuAdapter, currentInfo, widgetView);
            ListPopup popupMenu = new ListPopup(mainActivity);
            popupMenu.setAdapter(popupMenuAdapter);
            popupMenu.setOnItemClickListener((adapter, view, position) -> {
                @StringRes int stringId = ((ListPopup.Item) adapter.getItem(position)).stringId;
                popupMenuClickHandler(stringId, widgetView);
            });
            mainActivity.registerPopup(popupMenu);
            popupMenu.show(hostView);
            // Note: edit mode is NOT entered here; user must select "Move / Resize" from the popup.
            return true;
        });

        widgetArea.addView(hostView);
        mAppWidgetHost.startListening();
    }

    private void buildPopupMenu(Context context, ArrayAdapter<ListPopup.Item> adapter,
                                AppWidgetProviderInfo currentInfo, AppWidgetHostView widget) {
        adapter.add(new ListPopup.Item(context, R.string.menu_widget_edit));
        if (isReconfigurable(currentInfo)) {
            adapter.add(new ListPopup.Item(context, R.string.menu_widget_settings));
        }
        adapter.add(new ListPopup.Item(context, R.string.menu_widget_remove));
    }

    private void popupMenuClickHandler(@StringRes int stringId, WidgetView widget) {
        if (stringId == R.string.menu_widget_edit) {
            // Enter move/resize mode: the next touch on the widget will drag or resize it.
            widget.enterEditMode();
        } else if (stringId == R.string.menu_widget_settings) {
            reConfigureAppWidget(widget.getAppWidgetId());
        } else if (stringId == R.string.menu_widget_remove) {
            widgetArea.removeView(widget);
            mAppWidgetHost.deleteAppWidgetId(widget.getAppWidgetId());
            serializeState();
        }
    }

    // -------------------------------------------------------------------------
    // WidgetView.OnWidgetInteractionListener
    // -------------------------------------------------------------------------

    @Override
    public void onWidgetMoved(WidgetView view) {
        serializeState();
    }

    @Override
    public void onWidgetResized(WidgetView view) {
        serializeState();
    }

    // -------------------------------------------------------------------------
    // Adding new widget
    // -------------------------------------------------------------------------

    private void addAppWidget(int appWidgetId, AppWidgetProviderInfo appWidgetInfo) {
        int minWidthDp = appWidgetInfo.minWidth;
        int minHeightDp = getMinHeight(appWidgetInfo);
        if (minHeightDp <= 0) minHeightDp = DEFAULT_WIDGET_HEIGHT_DP;
        
        float density = mainActivity.getResources().getDisplayMetrics().density;
        int widthPx = (int) (minWidthDp * density);
        int heightPx = (int) (minHeightDp * density);
        
        // Wait until grid has dimensions to place widget
        widgetArea.post(() -> {
            int cellWidth = Math.max(1, widgetArea.getCellWidth());
            int cellHeight = Math.max(1, widgetArea.getCellHeight());
            
            int spanX = 1;
            int spanY = 1;
            spanX = Math.min(spanX, WidgetGridLayout.COLUMNS);
            
            int[] pos = widgetArea.findFirstEmptySpace(spanX, spanY);
            if (pos != null) {
                addWidget(appWidgetId, pos[0], pos[1], spanX, spanY);
            } else {
                // If grid appears full, just force place at bottom or 0,0 overlapping 
                // Alternatively, don't add, but we should add. We add at 0,0.
                Log.w(TAG, "No empty space in grid, forcing add at (0,0)");
                addWidget(appWidgetId, 0, 0, spanX, spanY);
            }
            serializeState();
        });
    }

    private void requestBindWidget(@NonNull Intent data) {
        final int appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        final ComponentName provider = data.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER);
        final UserHandle profile = data.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE);

        new Handler().postDelayed(() -> {
            Log.d(TAG, "asking for permission");
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, profile);
            requestAppWidgetBound.launch(intent);
        }, 500);
    }

    private void addAppWidget(Intent data) {
        int appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
        if (appWidgetInfo != null) {
            addAppWidget(appWidgetId, appWidgetInfo);

            if (!isConfigurationOptional(appWidgetInfo)) {
                configureAppWidget(appWidgetId, appWidgetInfo, REQUEST_APPWIDGET_CONFIGURED);
            }
        } else {
            Log.w(TAG, "Add widget not possible");
        }
    }

    private void reConfigureAppWidget(int appWidgetId) {
        AppWidgetProviderInfo appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
        configureAppWidget(appWidgetId, appWidgetInfo, REQUEST_APPWIDGET_RECONFIGURED);
    }

    private void configureAppWidget(int appWidgetId, AppWidgetProviderInfo appWidgetInfo, int requestCode) {
        if (appWidgetInfo != null && appWidgetInfo.configure != null) {
            mAppWidgetHost.startAppWidgetConfigureActivityForResult(mainActivity, appWidgetId, 0, requestCode, null);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isConfigurationOptional(@NonNull AppWidgetProviderInfo appWidgetInfo) {
        if (!isReconfigurable(appWidgetInfo)) {
            return false;
        }
        if (appWidgetInfo.configure != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int featureFlags = appWidgetInfo.widgetFeatures;
            return (featureFlags & WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0;
        } else {
            return false;
        }
    }

    private boolean isReconfigurable(@NonNull AppWidgetProviderInfo appWidgetInfo) {
        if (appWidgetInfo.configure != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int featureFlags = appWidgetInfo.widgetFeatures;
            return (featureFlags & WIDGET_FEATURE_RECONFIGURABLE) != 0;
        } else {
            return false;
        }
    }

    private int getMinHeight(AppWidgetProviderInfo appWidgetInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && appWidgetInfo.targetCellHeight > 0) {
            return (int) (appWidgetInfo.targetCellHeight * DrawableUtils.dpToPx(mainActivity, 50));
        } else {
            return appWidgetInfo.minHeight;
        }
    }

    private int dpToPx(int dp) {
        return (int) DrawableUtils.dpToPx(mainActivity, dp);
    }

    public void onStart() {
        mAppWidgetHost.startListening();
    }

    public void onDestroy() {
        mAppWidgetHost.stopListening();
    }
}
