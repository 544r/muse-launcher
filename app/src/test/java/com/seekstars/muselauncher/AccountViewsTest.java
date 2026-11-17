package com.seekstars.muselauncher;

import android.view.View;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AccountViewsTest {
    @Test public void sessionUpdateReusesAllViewsAndOnlyBindsChangedAccount() {
        LinearLayout list = new LinearLayout(RuntimeEnvironment.getApplication());
        int[] created = {0}, rebound = {0};
        AccountViews views = new AccountViews(list, (account, selected) -> {
            created[0]++;
            return new View(list.getContext());
        }, (view, account, selected) -> rebound[0]++);
        List<Account> accounts = new ArrayList<>();
        for (int i = 1; i <= 200; i++) accounts.add(new Account(i, "Account " + i));
        views.update(accounts, 1, a -> true);
        View first = list.getChildAt(0), last = list.getChildAt(199);
        assertEquals(200, created[0]);
        accounts.get(90).logged = true;
        views.update(accounts, 1, a -> true);
        assertEquals(200, created[0]);
        assertEquals(1, rebound[0]);
        assertSame(first, list.getChildAt(0));
        assertSame(last, list.getChildAt(199));
        views.update(accounts, 1, a -> true);
        assertEquals(1, rebound[0]);
        views.update(accounts, 2, a -> true);
        assertEquals(3, rebound[0]);
    }

    @Test public void searchReusesRowsAndDeletedAccountsAreRemoved() {
        LinearLayout list = new LinearLayout(RuntimeEnvironment.getApplication());
        AccountViews views = new AccountViews(list,
                (account, selected) -> new View(list.getContext()), (view, account, selected) -> {});
        List<Account> accounts = new ArrayList<>();
        accounts.add(new Account(1, "first"));
        accounts.add(new Account(2, "second"));
        views.update(accounts, 1, a -> true);
        View first = list.getChildAt(0), second = list.getChildAt(1);
        views.update(accounts, 1, a -> a.id == 2);
        assertEquals(1, list.getChildCount());
        assertSame(second, list.getChildAt(0));
        views.update(accounts, 1, a -> true);
        assertSame(first, list.getChildAt(0));
        assertSame(second, list.getChildAt(1));
        accounts.remove(0);
        views.update(accounts, 2, a -> true);
        assertEquals(1, list.getChildCount());
        assertSame(second, list.getChildAt(0));
    }
}
