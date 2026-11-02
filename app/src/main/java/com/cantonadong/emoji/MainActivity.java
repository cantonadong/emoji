package com.cantonadong.emoji;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.animation.ArgbEvaluator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.LinearInterpolator;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

public class MainActivity extends Activity {
    private static final int INK = 0xff25262e, MUTED = 0xff858997, ORANGE = 0xffef6b50, TILE = 0xfff5f5f7;
    private final ArrayList<Emoji> all = new ArrayList<>(), pinned = new ArrayList<>(), recent = new ArrayList<>(), random = new ArrayList<>();
    private final ArrayList<Object> rows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EmojiAdapter adapter = new EmojiAdapter();
    private Typeface emojiFont;
    private EditText search;
    private TextView clear, notice;
    private TextView title;
    private ImageView logo;
    private ImageButton back, language;
    private HorizontalScrollView tabScroll;
    private LinearLayout tabs;
    private boolean browsingAll;
    private String tappedEmoji = "";
    private long tappedAt;
    private long refreshStarted;
    private Runnable pendingPin;
    private int activeCategory = -1;
    private final int[] categoryPositions = new int[11];
    private final String[] categoryZh = {"表情", "爱心", "手势", "人物", "动物与自然", "食物", "旅行", "活动", "物品", "符号", "旗帜"};
    private final String[] categoryEn = {"Smileys", "Hearts", "Gestures", "People", "Nature", "Food", "Travel", "Activities", "Objects", "Symbols", "Flags"};
    private final String[] categoryIcons = {"😀", "❤️", "👍", "👥", "🐾", "🍔", "🚗", "⚽", "💡", "🔣", "🏳️"};
    private LinearLayout searchBox;
    private ListView list;
    private SharedPreferences prefs;
    private final Runnable hideNotice = () -> notice.setVisibility(View.GONE);

    private static class Emoji {
        String symbol, zh, en, searchable, countryCode = "";
        int category;
        ArrayList<String> keywords = new ArrayList<>();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("emoji", MODE_PRIVATE);
        try {
            emojiFont = Typeface.createFromAsset(getAssets(), "NotoColorEmoji.ttf");
            JSONArray data = new JSONArray(readAsset("emoji.json"));
            HashMap<String, Emoji> bySymbol = new HashMap<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject object = data.getJSONObject(i);
                Emoji e = new Emoji();
                e.symbol = object.getString("emoji");
                e.zh = object.getString("zh");
                e.en = object.getString("en");
                e.category = object.getInt("category");
                int[] points = e.symbol.codePoints().toArray();
                if (points.length == 2 && points[0] >= 0x1f1e6 && points[0] <= 0x1f1ff && points[1] >= 0x1f1e6 && points[1] <= 0x1f1ff) {
                    e.countryCode = "" + (char) ('a' + points[0] - 0x1f1e6) + (char) ('a' + points[1] - 0x1f1e6);
                }
                JSONArray words = object.getJSONArray("keywords");
                for (int j = 0; j < words.length(); j++) e.keywords.add(words.getString(j));
                e.searchable = normalize(e.symbol + " " + e.zh + " " + e.en + " " + e.countryCode + " " + TextUtils.join(" ", e.keywords));
                all.add(e);
                bySymbol.put(e.symbol, e);
            }
            JSONArray saved = new JSONArray(prefs.getString("recent", "[]"));
            for (int i = 0; i < saved.length() && recent.size() < 10; i++) {
                Emoji e = bySymbol.get(saved.optString(i));
                if (e != null && !recent.contains(e)) recent.add(e);
            }
            JSONArray savedPins = new JSONArray(prefs.getString("pinned", "[]"));
            for (int i = 0; i < savedPins.length() && pinned.size() < 10; i++) {
                Emoji e = bySymbol.get(savedPins.optString(i));
                if (e != null && !pinned.contains(e)) pinned.add(e);
            }
        } catch (IOException | JSONException | RuntimeException error) {
            TextView message = text(label("表情数据加载失败，请重新安装应用。", "Could not load emoji. Please reinstall the app."), 16, INK);
            message.setPadding(dp(24), dp(40), dp(24), 0);
            setContentView(message);
            return;
        }
        shuffle();
        buildScreen();
        if (state != null) {
            browsingAll = state.getBoolean("all", false);
            search.setText(state.getString("query", ""));
        }
        render();
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void buildScreen() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.WHITE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(16), dp(20), 0);
        frame.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = text("Emoji", 29, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back = new ImageButton(this);
        back.setImageResource(com.cantonadong.emoji.R.drawable.ic_back);
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> showAll(false));
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        logo = new ImageView(this);
        logo.setImageResource(com.cantonadong.emoji.R.drawable.ic_launcher);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        logoParams.rightMargin = dp(12);
        header.addView(logo, logoParams);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        language = new ImageButton(this);
        language.setImageResource(com.cantonadong.emoji.R.drawable.ic_language);
        language.setPadding(dp(12), dp(12), dp(12), dp(12));
        language.setBackground(new RippleDrawable(ColorStateList.valueOf(0x16000000), shape(TILE, 16, 0), null));
        language.setContentDescription("语言 / Language");
        header.addView(language, new LinearLayout.LayoutParams(dp(48), dp(48)));
        language.setOnClickListener(v -> showLanguageMenu(language));
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(52)));

        searchBox = new LinearLayout(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(12), 0, dp(4), 0);
        searchBox.setBackground(shape(TILE, 18, 0));
        ImageView magnifier = new ImageView(this);
        magnifier.setImageResource(com.cantonadong.emoji.R.drawable.ic_search);
        magnifier.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        magnifier.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        searchBox.addView(magnifier, new LinearLayout.LayoutParams(dp(30), -1));
        search = new EditText(this);
        search.setId(100);
        search.setTextSize(14);
        search.setTextColor(INK);
        search.setHintTextColor(0xff989cab);
        search.setHint("搜索表情、含义或关键词");
        search.setSingleLine(true);
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(dp(8), 0, 0, 0);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
        clear = text("×", 25, 0xff727785);
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("清空搜索");
        clear.setOnClickListener(v -> search.setText(""));
        searchBox.addView(clear, new LinearLayout.LayoutParams(dp(48), -1));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(56));
        searchParams.topMargin = dp(15);
        searchParams.bottomMargin = dp(6);
        page.addView(searchBox, searchParams);

        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setPadding(0, dp(8), 0, dp(8));
        tabScroll.addView(tabs);
        page.addView(tabScroll, new LinearLayout.LayoutParams(-1, dp(76)));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int state) {}
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (!browsingAll || total == 0) return;
                int category = 0;
                for (int i = 0; i < categoryPositions.length; i++) if (categoryPositions[i] <= first) category = i;
                if (first + visible == total && visible > 0 && view.getChildAt(visible - 1).getBottom() <= view.getHeight() - view.getPaddingBottom()) category = categoryPositions.length - 1;
                selectCategory(category);
            }
        });
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        page.setFocusableInTouchMode(true);
        page.requestFocus();

        notice = text("✓  已复制", 14, Color.WHITE);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(24), dp(14), dp(24), dp(14));
        notice.setBackground(shape(0xff25262c, 28, 0));
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        noticeParams.bottomMargin = dp(72);
        frame.addView(notice, noticeParams);
        setContentView(frame);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(); list.setSelection(0); }
            public void afterTextChanged(Editable e) {}
        });
    }

    private static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace("\ufe0f", "").trim();
    }

    private String label(String zh, String en) {
        String mode = prefs.getString("language", "auto");
        return (mode.equals("zh") || mode.equals("auto") && getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")) ? zh : en;
    }

    private void showLanguageMenu(View anchor) {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));
        PopupWindow popup = new PopupWindow(menu, dp(144), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(shape(Color.WHITE, 14, 0xffe9e9ee));
        popup.setElevation(dp(12));
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        String[] modes = {"auto", "zh", "en"};
        String[] names = {label("自动", "Automatic"), "中文", "English"};
        for (int i = 0; i < modes.length; i++) {
            String mode = modes[i];
            boolean active = mode.equals(prefs.getString("language", "auto"));
            LinearLayout option = new LinearLayout(this);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(dp(12), 0, dp(12), 0);
            option.setBackground(new RippleDrawable(ColorStateList.valueOf(0x16000000), shape(active ? TILE : Color.WHITE, 9, 0), null));
            option.addView(text(names[i], 14, INK), new LinearLayout.LayoutParams(0, -2, 1));
            TextView check = text(active ? "✓" : "", 17, INK);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            option.addView(check);
            option.setContentDescription(names[i]);
            option.setSelected(active);
            option.setOnClickListener(v -> {
                prefs.edit().putString("language", mode).apply();
                popup.dismiss();
                render();
            });
            menu.addView(option, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        popup.showAsDropDown(anchor, 0, dp(8), Gravity.RIGHT);
    }

    private void render() {
        String query = normalize(search.getText().toString());
        rows.clear();
        title.setText(browsingAll ? label("全部", "All") : "Emoji");
        logo.setVisibility(browsingAll ? View.GONE : View.VISIBLE);
        back.setVisibility(browsingAll ? View.VISIBLE : View.GONE);
        language.setVisibility(browsingAll ? View.GONE : View.VISIBLE);
        back.setContentDescription(label("返回", "Back"));
        searchBox.setVisibility(browsingAll ? View.GONE : View.VISIBLE);
        tabScroll.setVisibility(browsingAll ? View.VISIBLE : View.GONE);
        search.setHint(label("搜索表情、含义或关键词", "Search emoji or keywords"));
        clear.setContentDescription(label("清空搜索", "Clear search"));
        notice.setText(label("✓  已复制", "✓  Copied"));
        clear.setVisibility(query.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        searchBox.setBackground(shape(query.isEmpty() ? TILE : 0xfffff8f5, 18, query.isEmpty() ? 0 : 0xffffa48d));
        if (browsingAll) {
            buildTabs();
            for (int category = 0; category < categoryPositions.length; category++) {
                categoryPositions[category] = rows.size();
                rows.add("section:" + label(categoryZh[category], categoryEn[category]));
                ArrayList<Emoji> items = new ArrayList<>();
                for (Emoji e : all) if (e.category == category) items.add(e);
                addGrid(items);
            }
        } else if (query.isEmpty()) {
            rows.add("section:" + label("固定", "Pinned"));
            if (pinned.isEmpty()) rows.add("pin-empty:" + label("长按 emoji 以固定", "Hold an emoji to pin"));
            else addGrid(pinned);
            rows.add("section:" + label("最近使用", "Recent"));
            if (recent.isEmpty()) rows.add("empty:" + label("暂无记录", "No recent emoji"));
            else addGrid(recent);
            rows.add("section:" + label("随机推荐", "Discover"));
            addGrid(random);
            rows.add("refresh:");
        } else {
            ArrayList<Emoji> matches = new ArrayList<>();
            String[] terms = query.split("\\s+");
            for (Emoji e : all) {
                boolean hit = true;
                for (String term : terms) if (!e.searchable.contains(term)) { hit = false; break; }
                if (hit) matches.add(e);
            }
            matches.sort(Comparator.comparingInt(e -> e.countryCode.equals(query) || normalize(e.symbol).equals(query) || normalize(e.zh).equals(query) || normalize(e.en).equals(query) ? 0 : 1));
            rows.add("section:" + label("搜索结果", "Results"));
            if (matches.isEmpty()) rows.add("empty:" + label("没有找到表情", "No emoji found"));
            else addGrid(matches);
        }
        adapter.notifyDataSetChanged();
    }

    private void showAll(boolean show) {
        browsingAll = show;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
        render();
        list.setSelection(0);
    }

    private void buildTabs() {
        int previous = Math.max(0, activeCategory);
        tabs.removeAllViews();
        int width = tabScroll.getWidth() > 0 ? tabScroll.getWidth() : getResources().getDisplayMetrics().widthPixels - dp(40);
        int tabWidth = (width - dp(20)) / 5;
        for (int i = 0; i < categoryPositions.length; i++) {
            final int category = i;
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, 0, 0, 0);
            TextView icon = text(categoryIcons[i], 26, INK);
            icon.setTypeface(emojiFont);
            icon.setGravity(Gravity.CENTER);
            icon.setIncludeFontPadding(false);
            tab.addView(icon, new LinearLayout.LayoutParams(-1, 0, 1));
            View underline = new View(this);
            tab.addView(underline, new LinearLayout.LayoutParams(-1, dp(3)));
            tab.setContentDescription(label(categoryZh[i], categoryEn[i]));
            tab.setOnClickListener(v -> {
                list.setSelectionFromTop(categoryPositions[category], 0);
                selectCategory(category);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(tabWidth, dp(60));
            params.rightMargin = dp(5);
            tabs.addView(tab, params);
        }
        activeCategory = -1;
        selectCategory(previous);
    }

    private void selectCategory(int category) {
        if (category == activeCategory || tabs.getChildCount() == 0) return;
        activeCategory = category;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            LinearLayout tab = (LinearLayout) tabs.getChildAt(i);
            boolean active = i == category;
            tab.setSelected(active);
            GradientDrawable background = shape(active ? 0xffffeee5 : Color.WHITE, 0, 0);
            background.setColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{android.R.attr.state_selected}, new int[]{}}, new int[]{0xffffdac5, 0xffffeee5, Color.WHITE}));
            background.setCornerRadii(new float[]{dp(14), dp(14), dp(14), dp(14), 0, 0, 0, 0});
            tab.setBackground(background);
            tab.getChildAt(1).setBackgroundColor(active ? 0xffef6b50 : Color.TRANSPARENT);
        }
        View tab = tabs.getChildAt(category);
        tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, tab.getLeft() - (tabScroll.getWidth() - tab.getWidth()) / 2), 0));
    }

    private void addGrid(List<Emoji> items) {
        for (int i = 0; i < items.size(); i += 5) rows.add(new ArrayList<>(items.subList(i, Math.min(i + 5, items.size()))).toArray(new Emoji[0]));
    }

    private void shuffle() {
        ArrayList<Emoji> pool = new ArrayList<>(all);
        pool.removeAll(random);
        Collections.shuffle(pool);
        random.clear();
        random.addAll(pool.subList(0, Math.min(5, pool.size())));
    }

    private void copy(Emoji e) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText(label(e.zh, e.en), e.symbol));
        } catch (RuntimeException error) {
            Toast.makeText(this, label("复制失败，请重试", "Copy failed. Please try again."), Toast.LENGTH_SHORT).show();
            return;
        }
        recent.remove(e);
        recent.add(0, e);
        if (recent.size() > 10) recent.remove(10);
        JSONArray saved = new JSONArray();
        for (Emoji item : recent) saved.put(item.symbol);
        prefs.edit().putString("recent", saved.toString()).apply();
        if (pinned.remove(e)) {
            pinned.add(0, e);
            JSONArray savedPins = new JSONArray();
            for (Emoji item : pinned) savedPins.put(item.symbol);
            prefs.edit().putString("pinned", savedPins.toString()).apply();
        }
        if (search.length() > 0) search.setText("");
        else render();
        notice.setVisibility(View.VISIBLE);
        notice.announceForAccessibility(label(e.zh + "，已复制到剪贴板", e.en + ", copied to clipboard"));
        handler.removeCallbacks(hideNotice);
        handler.postDelayed(hideNotice, 1600);
    }

    private void pin(Emoji e, boolean removed) {
        pinned.remove(e);
        if (!removed) {
            pinned.add(0, e);
            if (pinned.size() > 10) pinned.remove(10);
        }
        JSONArray saved = new JSONArray();
        for (Emoji item : pinned) saved.put(item.symbol);
        prefs.edit().putString("pinned", saved.toString()).apply();
        browsingAll = false;
        if (!removed) copy(e);
        if (search.length() > 0) search.setText("");
        else render();
        list.setSelection(0);
        notice.setText(removed ? label("✓  已取消固定", "✓  Unpinned") : label("✓  已固定", "✓  Pinned"));
        notice.setVisibility(View.VISIBLE);
        notice.announceForAccessibility(label(e.zh, e.en) + "，" + notice.getText());
        handler.removeCallbacks(hideNotice);
        handler.postDelayed(hideNotice, 1600);
    }

    private ValueAnimator fade(GradientDrawable background, int from, int to, long duration) {
        ValueAnimator animation = ValueAnimator.ofArgb(from, to);
        animation.setDuration(duration);
        animation.addUpdateListener(value -> background.setColor((int) value.getAnimatedValue()));
        animation.start();
        return animation;
    }

    private void animateRefresh(ImageView icon) {
        long elapsed = SystemClock.uptimeMillis() - refreshStarted;
        icon.animate().cancel();
        if (elapsed >= 420) {
            icon.setRotation(0);
            return;
        }
        icon.setRotation(360f * elapsed / 420);
        icon.animate().rotation(360).setDuration(420 - elapsed).setInterpolator(new LinearInterpolator()).withEndAction(() -> icon.setRotation(0)).start();
    }

    private void cancelPinHold() {
        if (pendingPin != null) handler.removeCallbacks(pendingPin);
    }

    private void bindEmojiTouch(View tile, GradientDrawable background, Emoji e, boolean fromPinned) {
        final ValueAnimator[] animation = {null};
        final boolean[] longPressed = {false}, moved = {false};
        final float[] start = new float[2];
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        tile.setOnClickListener(v -> {
            tappedEmoji = e.symbol;
            tappedAt = SystemClock.uptimeMillis();
            copy(e);
        });
        tile.setOnLongClickListener(v -> { pin(e, fromPinned); return true; });
        tile.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                cancelPinHold();
                longPressed[0] = moved[0] = false;
                start[0] = event.getX();
                start[1] = event.getY();
                if (animation[0] != null) animation[0].cancel();
                animation[0] = fade(background, TILE, fromPinned ? 0xffff3030 : 0xffffdac5, 180);
                pendingPin = () -> { longPressed[0] = true; v.performLongClick(); };
                handler.postDelayed(pendingPin, 1000);
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (Math.abs(event.getX() - start[0]) > slop || Math.abs(event.getY() - start[1]) > slop) {
                    moved[0] = true;
                    cancelPinHold();
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
                cancelPinHold();
                int current = animation[0] == null ? TILE : (int) animation[0].getAnimatedValue();
                if (animation[0] != null) animation[0].cancel();
                animation[0] = fade(background, current, TILE, 280);
                if (action == MotionEvent.ACTION_UP && !longPressed[0] && !moved[0]) v.performClick();
                if (action == MotionEvent.ACTION_POINTER_DOWN) moved[0] = true;
            }
            return true;
        });
        long elapsed = SystemClock.uptimeMillis() - tappedAt;
        if (e.symbol.equals(tappedEmoji) && elapsed < 280) {
            int current = (int) new ArgbEvaluator().evaluate(elapsed / 280f, 0xffffeee5, TILE);
            background.setColor(current);
            animation[0] = fade(background, current, TILE, 280 - elapsed);
        }
    }

    private class EmojiAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }
        public boolean isEnabled(int position) { return false; }
        public int getViewTypeCount() { return 3; }
        public int getItemViewType(int position) { return rows.get(position) instanceof Emoji[] ? 0 : rows.get(position).equals("refresh:") ? 2 : 1; }

        public View getView(int position, View recycled, ViewGroup parent) {
            Object row = rows.get(position);
            if (row instanceof Emoji[]) {
                LinearLayout grid = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(MainActivity.this);
                grid.removeAllViews();
                grid.setPadding(0, 0, 0, dp(8));
                Emoji[] items = (Emoji[]) row;
                for (int i = 0; i < 5; i++) {
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(60), 1);
                    if (i < 4) params.rightMargin = dp(5);
                    if (i >= items.length) { grid.addView(new View(MainActivity.this), params); continue; }
                    Emoji e = items[i];
                    LinearLayout tile = new LinearLayout(MainActivity.this);
                    tile.setOrientation(LinearLayout.VERTICAL);
                    tile.setGravity(Gravity.CENTER);
                    tile.setPadding(0, dp(6), 0, dp(6));
                    GradientDrawable background = shape(TILE, 14, 0);
                    tile.setBackground(background);
                    TextView symbol = text(e.symbol, 30, INK);
                    symbol.setTypeface(emojiFont);
                    symbol.setGravity(Gravity.CENTER);
                    symbol.setIncludeFontPadding(false);
                    tile.addView(symbol, new LinearLayout.LayoutParams(-1, 0, 1));
                    tile.setContentDescription(label(e.zh + "，点击复制", e.en + ", tap to copy"));
                    tile.setFocusable(true);
                    symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    boolean fromPinned = !browsingAll && normalize(search.getText().toString()).isEmpty() && position > 0 && position <= (pinned.size() + 4) / 5;
                    bindEmojiTouch(tile, background, e, fromPinned);
                    grid.addView(tile, params);
                }
                return grid;
            }
            String value = row.toString();
            if (value.startsWith("pin-empty:")) {
                TextView hint = text(value.substring(10), 13, MUTED);
                hint.setGravity(Gravity.CENTER);
                hint.setPadding(0, 0, 0, dp(8));
                hint.setLayoutParams(new AbsListView.LayoutParams(-1, dp(68)));
                return hint;
            }
            if (value.equals("refresh:")) {
                if (recycled instanceof LinearLayout) {
                    LinearLayout wrapper = (LinearLayout) recycled;
                    LinearLayout button = (LinearLayout) wrapper.getChildAt(0);
                    ((TextView) button.getChildAt(1)).setText(label("换一换", "Shuffle"));
                    ((TextView) wrapper.getChildAt(1)).setText(label("查看全部 ›", "View all ›"));
                    animateRefresh((ImageView) button.getChildAt(0));
                    return wrapper;
                }
                LinearLayout wrapper = new LinearLayout(MainActivity.this);
                wrapper.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                wrapper.setPadding(0, dp(8), 0, dp(20));
                LinearLayout button = new LinearLayout(MainActivity.this);
                button.setGravity(Gravity.CENTER);
                button.setPadding(dp(18), 0, dp(18), 0);
                button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20ef6b50), shape(0xffffeee5, 24, 0), null));
                ImageView icon = new ImageView(MainActivity.this);
                icon.setImageResource(com.cantonadong.emoji.R.drawable.ic_refresh);
                icon.setColorFilter(0xffe75a26);
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(18), dp(18));
                iconParams.rightMargin = dp(8);
                button.addView(icon, iconParams);
                button.addView(text(label("换一换", "Shuffle"), 14, 0xffdc511e));
                button.setContentDescription(label("换一换随机表情", "Shuffle random emoji"));
                button.setOnClickListener(v -> {
                    refreshStarted = SystemClock.uptimeMillis();
                    animateRefresh(icon);
                    shuffle();
                    render();
                });
                wrapper.addView(button, new LinearLayout.LayoutParams(-2, dp(48)));
                TextView viewAll = text(label("查看全部 ›", "View all ›"), 14, INK);
                viewAll.setGravity(Gravity.CENTER);
                viewAll.setPadding(dp(18), 0, dp(18), 0);
                viewAll.setBackground(new RippleDrawable(ColorStateList.valueOf(0x16000000), shape(TILE, 24, 0), null));
                viewAll.setOnClickListener(v -> showAll(true));
                LinearLayout.LayoutParams viewAllParams = new LinearLayout.LayoutParams(-2, dp(48));
                viewAllParams.leftMargin = dp(12);
                wrapper.addView(viewAll, viewAllParams);
                animateRefresh(icon);
                return wrapper;
            }
            if (value.startsWith("section:")) {
                LinearLayout bar = new LinearLayout(MainActivity.this);
                bar.setGravity(Gravity.CENTER_VERTICAL);
                bar.setBaselineAligned(false);
                bar.setPadding(0, dp(8), 0, dp(8));
                if (!browsingAll && (value.equals("section:" + label("固定", "Pinned")) || value.equals("section:" + label("最近使用", "Recent")) || value.equals("section:" + label("随机推荐", "Discover")))) {
                    ImageView icon = new ImageView(MainActivity.this);
                    icon.setImageResource(value.equals("section:" + label("固定", "Pinned")) ? com.cantonadong.emoji.R.drawable.ic_pin : value.equals("section:" + label("最近使用", "Recent")) ? com.cantonadong.emoji.R.drawable.ic_recent : com.cantonadong.emoji.R.drawable.ic_sparkle);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(22), dp(22));
                    params.rightMargin = dp(8);
                    bar.addView(icon, params);
                }
                TextView title = text(value.substring(8), 18, INK);
                title.setGravity(Gravity.CENTER_VERTICAL);
                title.setIncludeFontPadding(false);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                bar.addView(title, new LinearLayout.LayoutParams(0, dp(34), 1));
                bar.setMinimumHeight(dp(56));
                return bar;
            }
            boolean empty = value.startsWith("empty:");
            TextView label = text(value.substring(value.indexOf(':') + 1), empty ? 13 : 12, MUTED);
            label.setPadding(0, dp(empty ? 18 : 12), 0, dp(empty ? 22 : 12));
            label.setLineSpacing(dp(5), 1);
            if (empty || value.startsWith("footer:")) label.setGravity(Gravity.CENTER);
            return label;
        }
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (search != null) state.putString("query", search.getText().toString());
        state.putBoolean("all", browsingAll);
    }

    @Override public void onBackPressed() {
        if (browsingAll) showAll(false);
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onPause() {
        cancelPinHold();
        super.onPause();
    }
}
