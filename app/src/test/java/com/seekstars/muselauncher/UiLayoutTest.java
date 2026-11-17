package com.seekstars.muselauncher;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Measure real Android layouts; also render review images, not golden-image assertions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UiLayoutTest {
    @Test public void configurationOnPhone() throws Exception { checkConfiguration(360, 800, 1f); }
    @Test public void configurationOnSmallPhoneWithLargeText() throws Exception { checkConfiguration(320, 568, 1.5f); }
    @Test public void configurationInLandscapeWithLargeText() throws Exception { checkConfiguration(640, 360, 1.5f); }
    @Test public void configurationOnTablet() throws Exception { checkConfiguration(800, 1000, 1.3f); }

    private void checkConfiguration(int width, int height, float fontScale) throws Exception {
        RuntimeEnvironment.setQualifiers("zh-rCN-w" + width + "dp-h" + height + "dp-mdpi");
        RuntimeEnvironment.setFontScale(fontScale);
        android.app.Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("accounts", 0).edit().clear().commit();
        AccountsStore store = new AccountsStore(app);
        Account account = store.addDefault();
        store.rename(account.id, "工作账号 · 日常创作");
        Intent intent = new Intent(app, AccountConfigActivity.class).putExtra("account_id", account.id);
        try (ActivityController<AccountConfigActivity> controller = Robolectric.buildActivity(AccountConfigActivity.class, intent).setup()) {
            AccountConfigActivity activity = controller.get();
            View root = activity.findViewById(R.id.configRoot);
            measure(root, width, height);
            View save = root.findViewById(R.id.cfgSave);
            assertTrue("save remains reachable", save.getBottom() <= height);
            assertTrue("save touch target", save.getHeight() >= 48);
            ScrollView scroll = root.findViewById(R.id.configScroll);
            assertTrue("form has scrollable space", scroll.getHeight() > 80);
            assertTrue("form has content", scroll.getChildAt(0).getHeight() > 0);
            for (int id : new int[]{R.id.cfgAvatar, R.id.cfgName, R.id.cfgTags, R.id.cfgProxy,
                    R.id.cfgProxyDetect, R.id.cfgProxyTest, R.id.cfgBack}) {
                View control = root.findViewById(id);
                assertTrue("control touch height " + id, control.getHeight() >= 48);
                assertTrue("control fits parent " + id, control.getRight() <= ((View) control.getParent()).getWidth());
                assertTrue("control has usable width " + id, control.getWidth() >= 48);
            }
            capture(root, "config-" + width + "-" + height);
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
            capture(root, "network-" + width + "-" + height);
        }
    }

    @Test public void accountDrawerWithLongNamesAndTags() throws Exception { checkDrawer(360, 800, 1f); }
    @Test public void accountDrawerAtSmallWidthWithLargeText() throws Exception { checkDrawer(320, 568, 1.5f); }

    @Test public void emptyStateActionIsReachableInLandscape() throws Exception {
        checkOverlay(R.id.emptyState, R.id.btnEmptyAdd, "empty-landscape");
    }

    @Test public void errorSettingsActionIsReachableInLandscape() throws Exception {
        checkOverlay(R.id.errorOverlay, R.id.btnErrorConfig, "error-landscape");
    }

    private void checkOverlay(int overlayId, int actionId, String imageName) throws Exception {
        RuntimeEnvironment.setQualifiers("zh-rCN-w640dp-h360dp-mdpi");
        RuntimeEnvironment.setFontScale(1.5f);
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_MuseLauncher);
        try (ActivityController<AppCompatActivity> ignored = controller.setup()) {
            AppCompatActivity activity = controller.get();
            activity.setContentView(R.layout.activity_main);
            View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            ScrollView overlay = root.findViewById(overlayId);
            overlay.setVisibility(View.VISIBLE);
            measure(root, 640, 360);
            overlay.scrollTo(0, overlay.getChildAt(0).getHeight());
            View action = root.findViewById(actionId);
            android.graphics.Rect bounds = new android.graphics.Rect();
            action.getDrawingRect(bounds);
            overlay.offsetDescendantRectToMyCoords(action, bounds);
            assertTrue("action within visible scrolled area", bounds.bottom - overlay.getScrollY() <= overlay.getHeight());
            assertTrue("action remains tappable", action.getHeight() >= 48);
            capture(root, imageName);
        }
    }

    private void checkDrawer(int width, int height, float fontScale) throws Exception {
        RuntimeEnvironment.setQualifiers("zh-rCN-w" + width + "dp-h" + height + "dp-mdpi");
        RuntimeEnvironment.setFontScale(fontScale);
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_MuseLauncher);
        try (ActivityController<AppCompatActivity> ignored = controller.setup()) {
            AppCompatActivity activity = controller.get();
            activity.setContentView(R.layout.activity_main);
            View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
            LinearLayout list = root.findViewById(R.id.accountList);
            Account account = new Account(1, "这是一个用于检查长名称显示的账号");
            account.tags = Arrays.asList("工作", "创作", "长标签");
            account.logged = true;
            View row = AccountRow.create(list, account, true, v -> {}, v -> {});
            list.addView(row);
            list.addView(AccountRow.create(list, new Account(2, "个人账号"), false, v -> {}, v -> {}));
            ((TextView) root.findViewById(R.id.accCount)).setText(activity.getString(R.string.accounts_count_fmt, 2));
            DrawerLayout drawer = root.findViewById(R.id.drawerLayout);
            View panel = root.findViewById(R.id.drawerContent);
            panel.getLayoutParams().width = Math.min(360, width - 64);
            drawer.openDrawer(panel, false);
            measure(root, width, height);
            View options = row.findViewById(R.id.accountOptions);
            assertEquals(48, options.getWidth());
            assertEquals(48, options.getHeight());
            assertTrue("menu fits card", options.getRight() <= row.getWidth());
            assertTrue("name gets usable space", row.findViewById(R.id.accountName).getWidth() >= 80);
            assertEquals(View.VISIBLE, row.findViewById(R.id.accountTags).getVisibility());
            assertTrue("card fits list", row.getRight() <= list.getWidth());
            capture(root, "drawer-" + width);
        }
    }

    private static void measure(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static void capture(View root, String name) throws Exception {
        File directory = new File("build/reports/ui");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { bitmap.recycle(); }
    }
}
