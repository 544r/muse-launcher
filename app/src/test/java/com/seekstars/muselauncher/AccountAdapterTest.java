package com.seekstars.muselauncher;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
public class AccountAdapterTest {
    private static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 300, 600);
    }

    private static List<AccountItem> items(int count) {
        List<AccountItem> result = new ArrayList<>();
        for (int i = 1; i <= count; i++) result.add(new AccountItem(new Account(i, "Account " + i)));
        return result;
    }

    @Test public void thousandAccountsCreateOnlyViewportHoldersAndReuseThemOnScroll() throws Exception {
        RuntimeEnvironment.setFontScale(1f);
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_MuseLauncher);
        try (ActivityController<AppCompatActivity> ignored = controller.setup()) {
            AppCompatActivity activity = controller.get();
            List<String> report = new ArrayList<>();
            report.add("accounts,previous_rows_created,new_holders_created,attached_rows");
            for (int count : new int[]{20, 200, 1000}) {
                // v1.2.3's eager creation is retained only here for a reproducible work-count comparison.
                LinearLayout previous = new LinearLayout(activity);
                List<Account> oldAccounts = new ArrayList<>();
                for (int i = 1; i <= count; i++) oldAccounts.add(new Account(i, "Account " + i));
                int[] oldCreated = {0};
                AccountViews baseline = new AccountViews(previous, (a, selected) -> {
                    oldCreated[0]++;
                    return AccountRow.create(previous, a, selected, v -> {}, v -> {});
                }, AccountRow::bind);
                baseline.update(oldAccounts, 1, a -> true);
                RecyclerView list = new RecyclerView(activity);
                list.setLayoutManager(new LinearLayoutManager(activity));
                list.setItemAnimator(null);
                int[] created = {0};
                AccountAdapter adapter = new AccountAdapter(false, id -> false, id -> {}, (v, id) -> {}) {
                    @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
                        created[0]++;
                        return super.onCreateViewHolder(parent, type);
                    }
                };
                adapter.submitList(items(count));
                list.setAdapter(adapter);
                activity.setContentView(list);
                layout(list);
                assertEquals(count, adapter.getItemCount());
                assertTrue("creation must remain bounded for " + count, created[0] < 20);
                assertTrue(list.getChildCount() < 20);
                report.add(count + "," + oldCreated[0] + "," + created[0] + "," + list.getChildCount());
                if (count == 1000) {
                    list.scrollToPosition(999);
                    layout(list);
                    RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(999);
                    assertNotNull(holder);
                    assertEquals("Account 1000", ((TextView) holder.itemView.findViewById(R.id.accountName)).getText().toString());
                    assertTrue("scroll reuses a bounded pool", created[0] < 40);
                }
            }
            File path = new File("build/reports/performance/list-work.csv");
            path.getParentFile().mkdirs();
            Files.write(path.toPath(), report, StandardCharsets.UTF_8);
        }
    }

    @Test public void recycledClicksAndStatusUseLatestAccountAndDoNotShiftScrollPosition() throws Exception {
        ActivityController<AppCompatActivity> controller = Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_MuseLauncher);
        try (ActivityController<AppCompatActivity> ignored = controller.setup()) {
            AppCompatActivity activity = controller.get();
            RecyclerView list = new RecyclerView(activity);
            LinearLayoutManager manager = new LinearLayoutManager(activity);
            list.setLayoutManager(manager);
            list.setItemAnimator(null);
            long[] click = {-1}, menu = {-1};
            boolean[] logged = {false};
            AccountAdapter adapter = new AccountAdapter(false, id -> logged[0], id -> click[0] = id, (v, id) -> menu[0] = id);
            adapter.submitList(items(200));
            list.setAdapter(adapter);
            activity.setContentView(list);
            list.scrollToPosition(120);
            layout(list);
            int first = manager.findFirstVisibleItemPosition();
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(first);
            int top = holder.itemView.getTop();
            long id = adapter.getItemId(first);
            logged[0] = true;
            adapter.setCurrent(id);
            adapter.refreshState(id);
            layout(list);
            assertEquals(first, manager.findFirstVisibleItemPosition());
            holder = list.findViewHolderForAdapterPosition(first);
            assertEquals(top, holder.itemView.getTop());
            assertEquals(View.VISIBLE, holder.itemView.findViewById(R.id.accountCurrent).getVisibility());
            assertEquals(activity.getString(R.string.account_session), ((TextView) holder.itemView.findViewById(R.id.accountStatus)).getText().toString());
            holder.itemView.performClick();
            holder.itemView.findViewById(R.id.accountOptions).performClick();
            assertEquals(id, click[0]);
            assertEquals(id, menu[0]);

            Account renamed = new Account(id, "Renamed account");
            CountDownLatch committed = new CountDownLatch(1);
            adapter.submitList(Collections.singletonList(new AccountItem(renamed)), committed::countDown);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (committed.getCount() != 0 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle();
                committed.await(10, TimeUnit.MILLISECONDS);
            }
            assertEquals(0, committed.getCount());
            layout(list);
            holder = list.findViewHolderForAdapterPosition(0);
            assertNotNull(holder);
            assertEquals("Renamed account", ((TextView) holder.itemView.findViewById(R.id.accountName)).getText().toString());
            holder.itemView.performClick();
            assertEquals(id, click[0]);
        }
    }
}
