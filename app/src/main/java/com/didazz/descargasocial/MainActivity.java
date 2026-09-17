// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Interfaz principal completamente nativa. La aplicación consulta la plataforma detectada
 * desde el dispositivo y escribe los medios seleccionados en Descargas/DescargaSocial.
 */
public final class MainActivity extends Activity {
    private static final int COLOR_BACKGROUND_TOP = 0xFF190B16;
    private static final int COLOR_BACKGROUND_BOTTOM = 0xFF07090E;
    private static final int COLOR_SURFACE = 0xF20F1119;
    private static final int COLOR_SURFACE_RAISED = 0xFF171A24;
    private static final int COLOR_BORDER = 0xFF303441;
    private static final int COLOR_TEXT = 0xFFF7F3F7;
    private static final int COLOR_MUTED = 0xFFA8A4AF;
    private static final int COLOR_PINK = 0xFFFF4387;
    private static final int COLOR_PINK_PRESSED = 0xFFE73574;
    private static final int COLOR_CORAL = 0xFFFF735F;
    private static final int COLOR_ERROR = 0xFFFF9AA6;
    private static final int COLOR_WARNING = 0xFFFFC46B;
    private static final int COLOR_SUCCESS = 0xFF72DFBA;
    private static final int REQUEST_STORAGE_PERMISSION = 4201;
    private static final int REQUEST_NOTIFICATIONS_PERMISSION = 4202;

    private final ExecutorService operationWorker = Executors.newSingleThreadExecutor();
    private ExecutorService previewWorker = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<CheckBox> itemChecks = new ArrayList<>();

    private EditText urlInput;
    private Button pasteButton;
    private Button analyzeButton;
    private Button downloadButton;
    private boolean notificationPromptHandled;
    private long seenDownloadRevision = -1;
    private final Runnable downloadStatusRefresh = new Runnable() {
        @Override public void run() {
            if (!isActivityInvalid()) {
                syncDownloadStatus();
                mainHandler.postDelayed(this, 700);
            }
        }
    };
    private Button selectAllButton;
    private Button clearAllButton;
    private Button qualityButton, clearLinkButton, newTab, downloadsTab;
    private boolean dataSaver, showingDownloads;
    private LinearLayout downloadsPage, taskList, selectionActions;
    private TextView tasksSummary, emptyHint;
    private CompactWorkspace composer;
    private final java.util.Map<String, TaskRow> taskRows = new java.util.LinkedHashMap<>();
    private LinearLayout statusCard;
    private TextView statusText;
    private ProgressBar progressBar;
    private LinearLayout previewSection;
    private LinearLayout previewRow;
    private TextView previewTitle;
    private TextView selectionText;

    private List<MediaItem> mediaItems = Collections.emptyList();
    private boolean[] selectedItems = new boolean[0];
    private String analyzedUrl;
    private ExtractionResult extractionResult;
    private final List<AlertDialog> openDialogs = new ArrayList<>();
    private final class LocalizedDialogBuilder extends AlertDialog.Builder {
        LocalizedDialogBuilder(Context context) { super(context); }
        @Override public AlertDialog show() {
            AlertDialog dialog = super.show();
            openDialogs.add(dialog);
            dialog.setOnDismissListener(ignored -> openDialogs.remove(dialog));
            return dialog;
        }
    }
    private void dismissLocalizedDialogs() {
        for (AlertDialog dialog : new ArrayList<>(openDialogs)) dialog.dismiss();
    }
    private boolean busy;
    private String lastStatus = Messages.ref("welcome");
    private StatusKind lastStatusKind = StatusKind.INFO;
    private boolean lastStatusProgress;

    private PendingDownloadRequest pendingPermissionDownload;
    private boolean destroyed;
    private String pendingSharedUrl;
    private int contentGeneration;

    @Override
    @SuppressWarnings("deprecation") // Required by the API 24-compatible system-bar path.
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        getWindow().setStatusBarColor(COLOR_BACKGROUND_TOP);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND_BOTTOM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        dataSaver = getPreferences(MODE_PRIVATE).getBoolean("data_saver", false);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(createContentView());
        showStatus(
                getString(R.string.welcome),
                StatusKind.INFO,
                false);

        if (savedInstanceState != null) {
            showPage(savedInstanceState.getBoolean("downloads", false));
            notificationPromptHandled = savedInstanceState.getBoolean("notification_prompt", false);
            String restoredUrl = savedInstanceState.getString("url");
            if (restoredUrl != null) {
                urlInput.setText(restoredUrl);
            }
        } else {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isActivityInvalid()) {
                        consumeIntent(getIntent(), true);
                    }
                }
            }, 180L);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        dismissLocalizedDialogs();
        String input = urlInput.getText().toString();
        int cursor = urlInput.getSelectionStart();
        boolean page = showingDownloads;
        List<MediaItem> retainedItems = mediaItems;
        boolean[] retainedSelection = selectedItems.clone();
        String retainedStatus = lastStatus;
        StatusKind retainedKind = lastStatusKind;
        boolean retainedProgress = lastStatusProgress;
        boolean retainedIndeterminate = progressBar.isIndeterminate();
        int retainedMax = progressBar.getMax();
        int retainedValue = progressBar.getProgress();
        taskRows.clear();
        itemChecks.clear();
        setContentView(createContentView());
        urlInput.setText(input);
        urlInput.setSelection(Math.max(0, Math.min(cursor, input.length())));
        if (!retainedItems.isEmpty()) {
            renderMedia(retainedItems, contentGeneration);
            for (int i = 0; i < retainedSelection.length; i++) {
                itemChecks.get(i).setChecked(retainedSelection[i]);
            }
        }
        showPage(page);
        setInteractiveEnabled(!busy);
        progressBar.setIndeterminate(retainedIndeterminate);
        progressBar.setMax(retainedMax);
        progressBar.setProgress(retainedValue);
        showStatus(retainedStatus, retainedKind, retainedProgress);
        seenDownloadRevision = -1;
        syncDownloadStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntent(intent, true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("url", urlInput == null ? "" : urlInput.getText().toString());
        outState.putBoolean("downloads", showingDownloads);
        outState.putBoolean("notification_prompt", notificationPromptHandled);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mainHandler.post(downloadStatusRefresh);
    }

    @Override
    protected void onStop() {
        mainHandler.removeCallbacks(downloadStatusRefresh);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        dismissLocalizedDialogs();
        destroyed = true;
        contentGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        operationWorker.shutdownNow();
        previewWorker.shutdownNow();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private View createContentView() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{COLOR_BACKGROUND_TOP, 0xFF091319, COLOR_BACKGROUND_BOTTOM}));
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout shell = new MaxWidthLinearLayout(this, dp(1000));
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(12), dp(4), dp(12), dp(8));
        screen.addView(shell, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.app_name), 21, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button about = smallButton(getString(R.string.about_symbol));
        about.setContentDescription(getString(R.string.about_accessibility));
        about.setOnClickListener(v -> showAboutDialog());
        header.addView(about, new LinearLayout.LayoutParams(dp(48), -2));
        shell.addView(header);

        LinearLayout tabs = new LinearLayout(this);
        newTab = smallButton(getString(R.string.new_tab));
        downloadsTab = smallButton(getString(R.string.downloads_tab));
        newTab.setOnClickListener(v -> showPage(false));
        downloadsTab.setOnClickListener(v -> { hideKeyboard(); showPage(true); });
        tabs.addView(newTab, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, -2, 1);
        tabParams.setMarginStart(dp(8));
        tabs.addView(downloadsTab, tabParams);
        shell.addView(tabs);
        shell.addView(space(8));
        FrameLayout pages = new FrameLayout(this);
        shell.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));

        composer = new CompactWorkspace(this);
        pages.addView(composer, new FrameLayout.LayoutParams(-1, -1));
        ScrollView editorScroll = new ScrollView(this);
        editorScroll.setFillViewport(false);
        editorScroll.addView(createUrlPanel(), new ScrollView.LayoutParams(-1, -2));
        composer.addView(editorScroll);
        composer.addView(createStatusCard());

        FrameLayout results = new FrameLayout(this);
        emptyHint = text(getString(R.string.empty_hint), 15, COLOR_MUTED);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(dp(16), dp(8), dp(16), dp(8));
        results.addView(emptyHint, new FrameLayout.LayoutParams(-1, -1));
        results.addView(createPreviewSection(), new FrameLayout.LayoutParams(-1, -1));
        composer.addView(results);
        analyzeButton = new Button(this);
        downloadButton = analyzeButton;
        stylePrimaryButton(analyzeButton);
        analyzeButton.setMinHeight(dp(48));
        analyzeButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        analyzeButton.setText(getString(R.string.analyze));
        analyzeButton.setOnClickListener(v -> {
            if (mediaItems.isEmpty()) { analyzeInput(); } else { requestDownload(); }
        });
        composer.addView(analyzeButton);

        downloadsPage = new LinearLayout(this);
        downloadsPage.setOrientation(LinearLayout.VERTICAL);
        LinearLayout taskHeader = new LinearLayout(this);
        taskHeader.setGravity(Gravity.CENTER_VERTICAL);
        tasksSummary = text(getString(R.string.no_requests), 14, COLOR_MUTED);
        taskHeader.addView(tasksSummary, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = smallButton(getString(R.string.clear_history));
        clear.setContentDescription(getString(R.string.clear_history_accessibility));
        clear.setOnClickListener(v -> { DownloadService.clearCompleted(this); syncDownloadStatus(); });
        taskHeader.addView(clear, new LinearLayout.LayoutParams(-2, -2));
        downloadsPage.addView(taskHeader);
        ScrollView taskScroll = new ScrollView(this);
        taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        taskScroll.addView(taskList, new ScrollView.LayoutParams(-1, -2));
        downloadsPage.addView(taskScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView note = text(getString(R.string.background_hint), 12, COLOR_MUTED);
        note.setPadding(0, dp(8), 0, 0);
        downloadsPage.addView(note);
        pages.addView(downloadsPage, new FrameLayout.LayoutParams(-1, -1));
        showPage(false);
        screen.requestApplyInsets();
        return screen;
    }

    private View createUrlPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(getString(R.string.content_link), 13, COLOR_MUTED);
        label.setLabelFor(4203);
        panel.addView(label);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        urlInput = new EditText(this);
        urlInput.setId(4203);
        urlInput.setHint(getString(R.string.link_hint));
        urlInput.setTextColor(COLOR_TEXT);
        urlInput.setHintTextColor(COLOR_MUTED);
        urlInput.setTextSize(16);
        urlInput.setSingleLine(true);
        urlInput.setMinHeight(dp(48));
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { analyzeInput(); return true; }
            return false;
        });
        urlInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        urlInput.setBackground(outlinedRounded(0xFF0B0D14, COLOR_BORDER, 12));
        row.addView(urlInput, new LinearLayout.LayoutParams(0, -2, 1));
        pasteButton = smallButton(getString(R.string.paste));
        pasteButton.setOnClickListener(v -> pasteFromClipboard());
        LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(-2, -2);
        pasteParams.setMarginStart(dp(6));
        row.addView(pasteButton, pasteParams);
        panel.addView(row);
        LinearLayout options = new LinearLayout(this);
        qualityButton = smallButton(dataSaver ? getString(R.string.quality_saver) : getString(R.string.quality_max));
        qualityButton.setOnClickListener(v -> showQualityDialog());
        options.addView(qualityButton, new LinearLayout.LayoutParams(0, -2, 1));
        clearLinkButton = smallButton(getString(R.string.new_link));
        clearLinkButton.setOnClickListener(v -> {
            clearMediaResults(); urlInput.setText(""); hideKeyboard();
            showStatus(Messages.ref("next_link"), StatusKind.INFO, false);
        });
        options.addView(clearLinkButton, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(options);
        urlInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!busy && analyzedUrl != null
                        && !analyzedUrl.equals(SocialLinkParser.extractSupportedUrl(s.toString()))) {
                    clearMediaResults();
                    showStatus(Messages.ref("link_changed"), StatusKind.INFO, false);
                }
            }
            public void afterTextChanged(android.text.Editable value) { }
        });
        return panel;
    }

    private void showQualityDialog() {
        new LocalizedDialogBuilder(this).setTitle(getString(R.string.quality_title))
                .setSingleChoiceItems(new String[]{getString(R.string.quality_best_option), getString(R.string.quality_saver_option)}, dataSaver ? 1 : 0,
                        (dialog, choice) -> {
                            boolean changed = dataSaver != (choice == 1);
                            dataSaver = choice == 1;
                            getPreferences(MODE_PRIVATE).edit().putBoolean("data_saver", dataSaver).apply();
                            qualityButton.setText(dataSaver ? getString(R.string.quality_saver) : getString(R.string.quality_max));
                            if (changed && !mediaItems.isEmpty()) {
                                clearMediaResults();
                                showStatus(Messages.ref("quality_changed"), StatusKind.INFO, false);
                            }
                            dialog.dismiss();
                        }).setNegativeButton(getString(R.string.close), null).show();
    }

    private View createStatusCard() {
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setMinimumHeight(dp(48));
        statusCard.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusText = text("", 13, COLOR_MUTED);
        statusText.setMaxLines(2);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusCard.addView(statusText);
        statusCard.setOnClickListener(v -> new LocalizedDialogBuilder(this).setTitle(getString(R.string.status))
                .setMessage(statusText.getText()).setPositiveButton(getString(R.string.close), null).show());
        statusCard.setContentDescription(getString(R.string.status_accessibility));
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(COLOR_PINK));
        progressBar.setProgressTintList(ColorStateList.valueOf(COLOR_PINK));
        progressBar.setVisibility(View.GONE);
        statusCard.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(4)));
        return statusCard;
    }

    private View createPreviewSection() {
        previewSection = new LinearLayout(this);
        previewSection.setOrientation(LinearLayout.VERTICAL);
        previewSection.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout inside = new LinearLayout(this);
        inside.setOrientation(LinearLayout.VERTICAL);
        previewTitle = text(getString(R.string.content), 17, COLOR_TEXT);
        previewTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        inside.addView(previewTitle);
        selectionText = text("", 13, COLOR_MUTED);
        inside.addView(selectionText);
        selectionActions = new LinearLayout(this);
        selectAllButton = smallButton(getString(R.string.select_all));
        clearAllButton = smallButton(getString(R.string.select_none));
        selectAllButton.setOnClickListener(v -> setAllSelected(true));
        clearAllButton.setOnClickListener(v -> setAllSelected(false));
        selectionActions.addView(selectAllButton, new LinearLayout.LayoutParams(0, -2, 1));
        selectionActions.addView(clearAllButton, new LinearLayout.LayoutParams(0, -2, 1));
        inside.addView(selectionActions);
        previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.VERTICAL);
        inside.addView(previewRow);
        scroll.addView(inside, new ScrollView.LayoutParams(-1, -2));
        previewSection.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        return previewSection;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        styleSecondaryButton(button, false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        return button;
    }
    private void showPage(boolean downloads) {
        showingDownloads = downloads;
        composer.setVisibility(downloads ? View.GONE : View.VISIBLE);
        downloadsPage.setVisibility(downloads ? View.VISIBLE : View.GONE);
        newTab.setTextColor(downloads ? COLOR_MUTED : COLOR_PINK);
        downloadsTab.setTextColor(downloads ? COLOR_PINK : COLOR_MUTED);
        newTab.setSelected(!downloads); downloadsTab.setSelected(downloads);
        if (downloads) { syncDownloadStatus(); }
    }

    private void consumeIntent(Intent intent, boolean analyzeAutomatically) {
        if (intent == null) {
            return;
        }
        if (intent.getBooleanExtra("show_downloads", false)) { showPage(true); return; }
        String candidate = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared != null) {
                candidate = SocialLinkParser.extractSupportedUrl(shared.toString());
            }
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            candidate = SocialLinkParser.extractSupportedUrl(intent.getData().toString());
        }

        if (candidate != null) {
            showPage(false);
            if (analyzeAutomatically && busy) {
                pendingSharedUrl = candidate;
                showStatus(
                        getString(R.string.share_pending),
                        StatusKind.INFO,
                        true);
                return;
            }
            urlInput.setText(candidate);
            urlInput.setSelection(candidate.length());
            if (analyzeAutomatically) {
                analyzeInput();
            }
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            showStatus(
                    getString(R.string.share_invalid),
                    StatusKind.ERROR,
                    false);
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            showStatus(Messages.ref("clipboard_empty"), StatusKind.ERROR, false);
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            showStatus(Messages.ref("clipboard_empty"), StatusKind.ERROR, false);
            return;
        }
        CharSequence copied = clip.getItemAt(0).coerceToText(this);
        String url = copied == null ? null : SocialLinkParser.extractSupportedUrl(copied.toString());
        if (url == null) {
            showStatus(
                    getString(R.string.clipboard_invalid),
                    StatusKind.ERROR,
                    false);
            return;
        }
        urlInput.setText(url);
        urlInput.setSelection(url.length());
        showStatus(Messages.ref("pasted"), StatusKind.INFO, false);
    }

    private void analyzeInput() {
        if (busy || isActivityInvalid()) {
            return;
        }
        String cleanUrl = SocialLinkParser.extractSupportedUrl(urlInput.getText().toString());
        SocialPlatform platform = SocialLinkParser.detectPlatform(cleanUrl);
        if (cleanUrl == null || platform == null || !SocialLinkParser.isSupportedPath(cleanUrl)) {
            showStatus(
                    getString(R.string.link_help),
                    StatusKind.ERROR,
                    false);
            return;
        }

        urlInput.setText(cleanUrl);
        urlInput.setSelection(cleanUrl.length());
        hideKeyboard();
        clearMediaResults();
        int generation = ++contentGeneration;
        final boolean useDataSaver = dataSaver;
        beginWork(
                Messages.ref(platform == SocialPlatform.GENERIC ? "query_web" : "query_platform",
                        platform.getDisplayName()),
                true,
                0);

        if (!submit(operationWorker, new Runnable() {
            @Override
            public void run() {
                try {
                    ExtractionResult result = SocialExtractor.extract(cleanUrl, useDataSaver);
                    List<MediaItem> items = result.getItems();
                    if (items == null || items.isEmpty()) {
                        throw new IOException(
                                Messages.ref("no_media_response", platform.getDisplayName()));
                    }
                    final List<MediaItem> safeResult = new ArrayList<>(items);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (generation != contentGeneration || isActivityInvalid()) {
                                return;
                            }
                            analyzedUrl = cleanUrl;
                            extractionResult = result;
                            renderMedia(safeResult, generation);
                            endWork();
                            String description;
                            if (platform == SocialPlatform.GENERIC) {
                                description = getString(R.string.main_video_found);
                            } else if (safeResult.size() > 1) {
                                description = Messages.ref("carousel_found", safeResult.size());
                            } else {
                                description = safeResult.get(0).isVideo()
                                        ? getString(R.string.video_ready)
                                        : getString(R.string.image_ready);
                            }
                            showStatus(description, StatusKind.SUCCESS, false);
                            processPendingShareIfAny();
                        }
                    });
                } catch (final Exception error) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (generation != contentGeneration || isActivityInvalid()) {
                                return;
                            }
                            endWork();
                            showStatus(friendlyError(error), StatusKind.ERROR, false);
                            processPendingShareIfAny();
                        }
                    });
                }
            }
        })) {
            endWork();
            showStatus(Messages.ref("closing"), StatusKind.ERROR, false);
        }
    }

    private void renderMedia(List<MediaItem> items, int generation) {
        mediaItems = items;
        selectedItems = new boolean[items.size()];
        itemChecks.clear(); previewRow.removeAllViews();
        previewTitle.setText(items.size() > 1 ? getString(R.string.content_found)
                : items.get(0).isVideo() ? getString(R.string.video_found) : getString(R.string.image_found));
        selectionActions.setVisibility(items.size() > 1 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            MediaItem item = items.get(i);
            selectedItems[i] = true;
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(8), dp(8), dp(8), dp(8));
            card.setBackground(outlinedRounded(COLOR_SURFACE, COLOR_BORDER, 12));
            FrameLayout picture = new FrameLayout(this);
            ImageView preview = new ImageView(this);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setContentDescription(getString(R.string.preview_item, i + 1));
            picture.addView(preview, new FrameLayout.LayoutParams(-1, -1));
            TextView placeholder = text(item.isVideo() ? "▶" : "▧", 28, COLOR_MUTED);
            placeholder.setGravity(Gravity.CENTER);
            picture.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            card.addView(picture, new LinearLayout.LayoutParams(dp(80), dp(64)));
            CheckBox check = new CheckBox(this);
            String dimensions = item.getWidth() > 0 ? "\n" + item.getWidth() + " × " + item.getHeight() : "";
            check.setText(getString(item.isVideo() ? R.string.video_item : R.string.image_item, i + 1) + dimensions);
            check.setTextColor(COLOR_TEXT); check.setTextSize(14);
            check.setMinHeight(dp(48));
            check.setChecked(true);
            check.setButtonTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{COLOR_PINK, COLOR_MUTED}));
            check.setOnCheckedChangeListener((button, selected) -> {
                if (index < selectedItems.length) { selectedItems[index] = selected; updateSelectionUi(); }
            });
            card.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
            itemChecks.add(check);
            card.setOnClickListener(v -> { if (!busy) { check.setChecked(!check.isChecked()); } });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(6);
            previewRow.addView(card, params);
            loadPreview(item, preview, placeholder, generation);
        }
        emptyHint.setVisibility(View.GONE);
        previewSection.setVisibility(View.VISIBLE);
        composer.requestLayout();
        updateSelectionUi();
    }

    private void loadPreview(
            MediaItem item,
            ImageView imageView,
            TextView placeholder,
            int generation) {
        String previewUrl = firstNonBlank(item.getPreviewUrl(), item.getThumbnailUrl());
        if (previewUrl == null && !item.isVideo()) {
            previewUrl = item.getUrl();
        }
        if (previewUrl == null) {
            return;
        }
        submit(previewWorker, new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] bytes = SocialExtractor.fetchPreview(item);
                    final Bitmap bitmap = decodePreview(bytes, dp(408), dp(352));
                    if (bitmap == null) {
                        return;
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (generation == contentGeneration && !isActivityInvalid()) {
                                imageView.setImageBitmap(bitmap);
                                placeholder.setVisibility(View.GONE);
                            } else {
                                bitmap.recycle();
                            }
                        }
                    });
                } catch (Exception ignored) {
                    // Una previsualización fallida no impide descargar el medio original.
                }
            }
        });
    }

    private Bitmap decodePreview(byte[] bytes, int targetWidth, int targetHeight) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > targetWidth * 2
                || bounds.outHeight / sample > targetHeight * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void setAllSelected(boolean selected) {
        if (busy) {
            return;
        }
        for (CheckBox check : itemChecks) {
            check.setChecked(selected);
        }
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        int count = selectedCount(), total = selectedItems.length;
        selectionText.setText(getString(R.string.selected_count, count, total));
        downloadButton.setText(mediaItems.isEmpty() ? getString(R.string.analyze) : getString(R.string.download_count, count));
        downloadButton.setEnabled(!busy && (mediaItems.isEmpty() || count > 0));
        downloadButton.setAlpha(downloadButton.isEnabled() ? 1f : 0.48f);
        selectAllButton.setEnabled(!busy && count < total);
        clearAllButton.setEnabled(!busy && count > 0);
    }

    private int selectedCount() {
        int count = 0;
        for (boolean selected : selectedItems) {
            if (selected) {
                count++;
            }
        }
        return count;
    }

    private void requestDownload() {
        if (busy || mediaItems.isEmpty() || selectedCount() == 0) {
            return;
        }
        String currentUrl = SocialLinkParser.extractSupportedUrl(urlInput.getText().toString());
        if (currentUrl == null
                || analyzedUrl == null
                || extractionResult == null
                || !currentUrl.equals(analyzedUrl)) {
            showStatus(
                    getString(R.string.link_changed_download),
                    StatusKind.ERROR,
                    false);
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionDownload = createDownloadRequest();
            beginWork(Messages.ref("waiting_storage"), true, 0);
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
            return;
        }
        performDownload(createDownloadRequest());
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PendingDownloadRequest pending = pendingPermissionDownload;
        if (requestCode == REQUEST_NOTIFICATIONS_PERMISSION && pending != null) {
            pendingPermissionDownload = null;
            endWork();
            performDownload(pending);
            return;
        }
        if (requestCode != REQUEST_STORAGE_PERMISSION || pending == null) {
            return;
        }
        pendingPermissionDownload = null;
        endWork();
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            performDownload(pending);
        } else {
            showStatus(
                    getString(R.string.storage_permission),
                    StatusKind.ERROR,
                    false);
            processPendingShareIfAny();
        }
    }

    private PendingDownloadRequest createDownloadRequest() {
        List<DownloadSelection> downloads = new ArrayList<>();
        for (int i = 0; i < mediaItems.size(); i++) {
            if (selectedItems[i]) {
                downloads.add(new DownloadSelection(mediaItems.get(i), i + 1));
            }
        }
        String contentId = safeContentId(extractionResult.getContentId());
        String label = extractionResult.getPlatform().getDisplayName() + " · " + contentId;
        return new PendingDownloadRequest(downloads, contentId, label);
    }

    private void performDownload(PendingDownloadRequest request) {
        if (request == null || request.downloads.isEmpty() || isActivityInvalid()) { return; }
        if (Build.VERSION.SDK_INT >= 33 && !notificationPromptHandled
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPromptHandled = true;
            pendingPermissionDownload = request;
            beginWork(Messages.ref("notification_permission"), true, 0);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS_PERMISSION);
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (DownloadSelection selection : request.downloads) {
            items.add(selection.item);
            positions.add(selection.originalIndex);
        }
        try {
            String taskId = DownloadService.start(this, items, positions, request.contentId, request.label);
            String added = null;
            for (DownloadQueue.Snapshot task : DownloadService.getStates(this)) {
                if (task.id.equals(taskId)) { added = task.label.split(" · ", 2)[0]; }
            }
            endWork();
            clearMediaResults();
            urlInput.setText("");
            showStatus(added == null ? Messages.ref("request_added_hint") : Messages.ref("request_added_named", added),
                    StatusKind.SUCCESS, false);
            syncDownloadStatus();
            processPendingShareIfAny();
        } catch (RuntimeException error) {
            endWork();
            showStatus(error instanceof IllegalStateException ? TextResources.error(this, error.getMessage())
                    : getString(R.string.start_failed), StatusKind.ERROR, false);
        }
    }

    private void syncDownloadStatus() {
        long revision = DownloadService.revision(this);
        if (revision == seenDownloadRevision) { return; }
        seenDownloadRevision = revision;
        List<DownloadQueue.Snapshot> states = DownloadService.getStates(this);
        List<DownloadQueue.Snapshot> ordered = new ArrayList<>();
        for (DownloadQueue.Snapshot s : states) { if (s.running()) { ordered.add(s); } }
        for (DownloadQueue.Snapshot s : states) {
            if (s.phase == DownloadQueue.Phase.QUEUED) { ordered.add(s); }
        }
        for (int i = states.size() - 1; i >= 0; i--) {
            if (!states.get(i).active()) { ordered.add(states.get(i)); }
        }
        int active = 0, running = 0;
        java.util.Set<String> present = new java.util.HashSet<>();
        for (DownloadQueue.Snapshot state : ordered) {
            present.add(state.id);
            if (state.active()) { active++; }
            if (state.running()) { running++; }
            TaskRow row = taskRows.get(state.id);
            if (row == null) {
                row = new TaskRow(state.id);
                taskRows.put(state.id, row);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.bottomMargin = dp(8);
                taskList.addView(row.card, params);
            }
            row.update(state);
        }
        java.util.Iterator<java.util.Map.Entry<String, TaskRow>> it = taskRows.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, TaskRow> entry = it.next();
            if (!present.contains(entry.getKey())) { taskList.removeView(entry.getValue().card); it.remove(); }
        }
        for (int i = 0; i < ordered.size(); i++) {
            View card = taskRows.get(ordered.get(i).id).card;
            if (taskList.getChildAt(i) != card) {
                taskList.removeView(card); taskList.addView(card, i);
            }
        }
        downloadsTab.setText(active == 0 ? getString(R.string.downloads_tab) : getString(R.string.downloads_count, active));
        tasksSummary.setText(states.isEmpty() ? getString(R.string.no_requests_yet)
                : getString(R.string.queue_summary, running, active - running));
    }

    private final class TaskRow {
        final LinearLayout card = new LinearLayout(MainActivity.this);
        final TextView title = text("", 15, COLOR_TEXT);
        final TextView message = text("", 13, COLOR_MUTED);
        final ProgressBar progress = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
        final Button cancel = smallButton(getString(R.string.cancel));
        DownloadQueue.Snapshot latest;
        TaskRow(String id) {
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(8), dp(12), dp(8));
            card.setBackground(outlinedRounded(COLOR_SURFACE, COLOR_BORDER, 14));
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(title);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            message.setMaxLines(3); message.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
            card.addView(row);
            progress.setMax(100); progress.setProgressTintList(ColorStateList.valueOf(COLOR_PINK));
            progress.setIndeterminateTintList(ColorStateList.valueOf(COLOR_PINK));
            card.addView(progress, new LinearLayout.LayoutParams(-1, dp(5)));
            cancel.setOnClickListener(v -> {
                if(latest!=null && !latest.active()) { showDetails(); return; }
                try { DownloadService.cancel(MainActivity.this, id); }
                catch (RuntimeException error) {
                    new LocalizedDialogBuilder(MainActivity.this).setMessage(getString(R.string.cancel_failed))
                            .setPositiveButton(getString(R.string.close), null).show();
                }
            });
            card.setOnClickListener(v -> showDetails());
        }
        void showDetails() {
            if(latest==null)return;
            String detail=TextResources.render(MainActivity.this, latest.label+"\n\n"+latest.message);
            new LocalizedDialogBuilder(MainActivity.this).setTitle(getString(R.string.request_details))
                    .setMessage(detail).setNeutralButton(getString(R.string.copy),(dialog,which)->{
                        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_diagnostic),detail));
                    }).setPositiveButton(getString(R.string.close),null).show();
        }
        void update(DownloadQueue.Snapshot state) {
            latest = state;
            title.setText(TextResources.render(MainActivity.this, state.label));
            String prefix;
            switch (state.phase) {
                case COMPLETE: prefix = getString(R.string.complete_prefix); break;
                case FAILED: prefix = getString(R.string.failed_prefix); break;
                case CANCELLED: prefix = getString(R.string.cancelled_prefix); break;
                case CANCELLING: prefix = getString(R.string.cancelling_prefix); break;
                default: prefix = "";
            }
            String rendered = TextResources.render(MainActivity.this, state.message);
            int diagnostic = rendered.indexOf("\n\n");
            message.setText(prefix + (diagnostic < 0 ? rendered : rendered.substring(0, diagnostic)));
            message.setTextColor(state.phase == DownloadQueue.Phase.COMPLETE ? COLOR_SUCCESS
                    : state.phase == DownloadQueue.Phase.FAILED ? COLOR_ERROR : COLOR_MUTED);
            cancel.setVisibility(state.active() || state.phase==DownloadQueue.Phase.FAILED ? View.VISIBLE : View.GONE);
            cancel.setText(state.active()?getString(R.string.cancel):getString(R.string.details));
            cancel.setEnabled(state.phase != DownloadQueue.Phase.CANCELLING);
            cancel.setContentDescription(getString(state.active() ? R.string.cancel_named : R.string.details_named,
                    TextResources.render(MainActivity.this, state.label)));
            progress.setVisibility(state.running() ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(state.expected <= 0);
            progress.setProgress(state.percent());
        }
    }

    private void beginWork(String message, boolean indeterminate, int max) {
        busy = true;
        setInteractiveEnabled(false);
        progressBar.setIndeterminate(indeterminate);
        if (!indeterminate) {
            progressBar.setMax(Math.max(1, max));
            progressBar.setProgress(0);
        }
        showStatus(message, StatusKind.INFO, true);
    }

    private void endWork() {
        busy = false;
        progressBar.setVisibility(View.GONE);
        setInteractiveEnabled(true);
    }

    private boolean submit(ExecutorService executor, Runnable task) {
        if (isActivityInvalid()) {
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private boolean isActivityInvalid() {
        return destroyed
                || isFinishing()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed());
    }

    private void processPendingShareIfAny() {
        if (busy || isActivityInvalid() || pendingSharedUrl == null) {
            return;
        }
        String nextUrl = pendingSharedUrl;
        pendingSharedUrl = null;
        urlInput.setText(nextUrl);
        urlInput.setSelection(nextUrl.length());
        analyzeInput();
    }

    private void setInteractiveEnabled(boolean enabled) {
        urlInput.setEnabled(enabled);
        pasteButton.setEnabled(enabled);
        analyzeButton.setEnabled(enabled);
        qualityButton.setEnabled(enabled);
        clearLinkButton.setEnabled(enabled);
        for (CheckBox check : itemChecks) {
            check.setEnabled(enabled);
        }
        updateSelectionUi();
        pasteButton.setAlpha(enabled ? 1f : 0.55f);
    }

    private void clearMediaResults() {
        contentGeneration++;
        previewWorker.shutdownNow();
        previewWorker = Executors.newFixedThreadPool(2);
        mediaItems = Collections.emptyList();
        selectedItems = new boolean[0];
        itemChecks.clear();
        analyzedUrl = null;
        extractionResult = null;
        if (previewRow != null) {
            previewRow.removeAllViews();
        }
        if (previewSection != null) {
            previewSection.setVisibility(View.GONE);
            emptyHint.setVisibility(View.VISIBLE);
            composer.requestLayout();
            updateSelectionUi();
        }
    }

    private void showStatus(String message, StatusKind kind, boolean showProgress) {
        int textColor;
        int background;
        int border;
        String prefix;
        switch (kind) {
            case SUCCESS:
                textColor = COLOR_SUCCESS;
                background = 0xFF10231F;
                border = 0xFF285F52;
                prefix = "✓  ";
                break;
            case WARNING:
                textColor = COLOR_WARNING;
                background = 0xFF271C10;
                border = 0xFF684821;
                prefix = "!  ";
                break;
            case ERROR:
                textColor = COLOR_ERROR;
                background = 0xFF2B0D13;
                border = 0xFF70202E;
                prefix = "×  ";
                break;
            default:
                textColor = 0xFFB8C4D7;
                background = 0xFF111723;
                border = 0xFF303D52;
                prefix = "↳  ";
                break;
        }
        lastStatus = TextResources.defer(this, message);
        lastStatusKind = kind;
        lastStatusProgress = showProgress;
        statusText.setText(prefix + TextResources.render(this, lastStatus));
        statusText.setTextColor(textColor);
        statusCard.setBackground(outlinedRounded(background, border, 16));
        progressBar.setVisibility(showProgress ? View.VISIBLE : View.GONE);
    }

    private String friendlyError(Throwable error) {
        if (error instanceof YoutubeException) { return TextResources.error(this, error.getMessage()); }
        Throwable cause = rootCause(error);
        String originalMessage = cause.getMessage() == null ? "" : cause.getMessage();
        String message = originalMessage.toLowerCase(Locale.ROOT);
        String platformName = platformNameForUi();
        if (cause instanceof UnknownHostException
                || cause instanceof ConnectException
                || cause instanceof SocketTimeoutException
                || message.contains("timeout")
                || message.contains("network")) {
            return getString(R.string.connection_failed);
        }
        if (message.contains("429")
                || message.contains("rate limit")
                || message.contains("try again later")
                || message.contains("limitado temporalmente")) {
            return Messages.ref("query_limited", platformName);
        }
        if (message.contains("cambiado el formato") || message.contains("actualizar la aplicación")) {
            return Messages.ref("query_changed", platformName);
        }
        if (message.contains("historias de facebook")
                || message.startsWith("facebook exige iniciar sesión")
                || message.startsWith("facebook no ofrece")
                || message.startsWith("facebook no entregó")
                || message.startsWith("instagram no ofrece")) {
            return TextResources.error(this, originalMessage);
        }
        if (SocialPlatform.GENERIC.getDisplayName().equals(platformName)
                && !originalMessage.trim().isEmpty()) {
            return TextResources.error(this, originalMessage);
        }
        if (message.contains("401")
                || message.contains("403")
                || message.contains("blocked")
                || message.contains("challenge")) {
            return Messages.ref("query_rejected", platformName);
        }
        if (message.contains("private")
                || message.contains("login")
                || message.contains("not found")
                || message.contains("deleted")
                || message.contains("unavailable")
                || message.contains("requiere acceso")) {
            return getString(R.string.login_required);
        }
        if (message.contains("shortcode") || message.contains("url") || message.contains("enlace")) {
            return getString(R.string.unsupported_link);
        }
        return Messages.ref("query_empty", platformName);
    }

    private String platformNameForUi() {
        if (extractionResult != null) {
            return extractionResult.getPlatform().getDisplayName();
        }
        String text = urlInput == null ? null : urlInput.getText().toString();
        SocialPlatform platform = SocialLinkParser.detectPlatform(
                SocialLinkParser.extractSupportedUrl(text));
        return platform == null ? getString(R.string.platform) : platform.getDisplayName();
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String safeContentId(String contentId) {
        if (contentId == null) {
            return "contenido";
        }
        String safe = contentId.replaceAll("[^A-Za-z0-9_-]", "");
        if (safe.isEmpty()) {
            return "contenido";
        }
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        if (second != null && !second.trim().isEmpty()) {
            return second;
        }
        return null;
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }

    private void showAboutDialog() {
        new LocalizedDialogBuilder(this)
                .setTitle(getString(R.string.about_title, "1.5.0"))
                .setMessage(getString(R.string.short_description) + "\n\n"
                        + getString(R.string.full_description) + "\n\n" + getString(R.string.about_notes))
                .setNeutralButton(getString(R.string.licenses), (dialog, which) -> showLicenses())
                .setPositiveButton(getString(R.string.understood), null)
                .show();
    }

    private void showLicenses() {
        try {
            String[] names=getAssets().list("licenses");
            if(names==null) { throw new IOException(getString(R.string.no_licenses)); }
            new LocalizedDialogBuilder(this).setTitle(getString(R.string.software_licenses))
                    .setItems(names,(dialog,index)->{
                        try(java.io.InputStream in=getAssets().open("licenses/"+names[index])) {
                            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                            byte[] buffer=new byte[8192];int n;
                            while((n=in.read(buffer))!=-1) { bytes.write(buffer,0,n); }
                            new LocalizedDialogBuilder(this).setTitle(names[index])
                                    .setMessage(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8))
                                    .setPositiveButton(getString(R.string.close),null).show();
                        } catch(IOException error) {
                            new LocalizedDialogBuilder(this).setMessage(getString(R.string.license_open_failed))
                                    .setPositiveButton(getString(R.string.close),null).show();
                        }
                    }).setPositiveButton(getString(R.string.close),null).show();
        } catch(IOException error) {
            new LocalizedDialogBuilder(this).setMessage(getString(R.string.licenses_read_failed))
                    .setPositiveButton(getString(R.string.close),null).show();
        }
    }

    private TextView text(String value, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }

    private View space(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return view;
    }

    private void stylePrimaryButton(Button button) {
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(15), 0, dp(15), 0);
        StateListDrawable background = new StateListDrawable();
        background.addState(
                new int[]{-android.R.attr.state_enabled},
                gradientRounded(0xFF6D3E51, 0xFF70433D, 17));
        background.addState(
                new int[]{android.R.attr.state_pressed},
                gradientRounded(COLOR_PINK_PRESSED, 0xFFE75D4D, 17));
        background.addState(
                new int[]{},
                gradientRounded(COLOR_PINK, COLOR_CORAL, 17));
        button.setBackground(background);
        button.setElevation(dp(5));
    }

    private void styleSecondaryButton(Button button, boolean compact) {
        button.setAllCaps(false);
        button.setTextSize(compact ? 14 : 13);
        button.setTextColor(COLOR_TEXT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(compact ? dp(84) : 0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(selectableRounded(COLOR_SURFACE_RAISED, 0xFF272B37, 13));
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private GradientDrawable outlinedRounded(int color, int strokeColor, int radiusDp) {
        GradientDrawable background = rounded(color, radiusDp);
        background.setStroke(dp(1), strokeColor);
        return background;
    }

    private GradientDrawable gradientRounded(int start, int end, int radiusDp) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{start, end});
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private StateListDrawable selectableRounded(int normal, int pressed, int radiusDp) {
        StateListDrawable background = new StateListDrawable();
        background.addState(
                new int[]{android.R.attr.state_pressed},
                outlinedRounded(pressed, 0xFF4B5060, radiusDp));
        background.addState(
                new int[]{},
                outlinedRounded(normal, COLOR_BORDER, radiusDp));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum StatusKind {
        INFO,
        SUCCESS,
        WARNING,
        ERROR
    }

    private static final class DownloadSelection {
        private final MediaItem item;
        private final int originalIndex;

        private DownloadSelection(MediaItem item, int originalIndex) {
            this.item = item;
            this.originalIndex = originalIndex;
        }
    }

    private static final class PendingDownloadRequest {
        private final List<DownloadSelection> downloads;
        private final String contentId;
        private final String label;

        private PendingDownloadRequest(List<DownloadSelection> downloads, String contentId, String label) {
            this.downloads = Collections.unmodifiableList(new ArrayList<>(downloads));
            this.contentId = contentId;
            this.label = label;
        }
    }

    private final class CompactWorkspace extends ViewGroup {
        CompactLayout geometry;
        CompactWorkspace(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            boolean wide = width >= dp(600) && width > height * 1.2;
            int editorWidth = wide ? (width - dp(8)) / 2 : width;
            getChildAt(0).measure(MeasureSpec.makeMeasureSpec(editorWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int desiredEditor = getChildAt(0).getMeasuredHeight();
            getChildAt(1).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            getChildAt(3).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            geometry = new CompactLayout(width, height, desiredEditor, getChildAt(1).getMeasuredHeight(),
                    getChildAt(3).getMeasuredHeight(), dp(8), wide, !mediaItems.isEmpty());
            CompactLayout.Box[] boxes = {geometry.editor, geometry.status, geometry.content, geometry.action};
            for (int i = 0; i < boxes.length; i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(boxes[i].width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(boxes[i].height, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, height);
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            CompactLayout.Box[] boxes = {geometry.editor, geometry.status, geometry.content, geometry.action};
            for (int i = 0; i < boxes.length; i++) {
                CompactLayout.Box b = boxes[i];
                getChildAt(i).layout(b.x, b.y, b.x + b.width, b.y + b.height);
            }
        }
    }

    private static final class MaxWidthLinearLayout extends LinearLayout {
        private final int maxWidth;

        private MaxWidthLinearLayout(Context context, int maxWidth) {
            super(context);
            this.maxWidth = maxWidth;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int available = MeasureSpec.getSize(widthMeasureSpec);
            int mode = MeasureSpec.getMode(widthMeasureSpec);
            if (mode != MeasureSpec.UNSPECIFIED && available > maxWidth) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
