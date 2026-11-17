package com.seekstars.muselauncher;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.widget.EditText;
import org.junit.Before;
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
@Config(sdk = 34)
public class AccountConfigActivityTest {
    private Application app;
    private long accountId;
    private Message request;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("accounts", 0).edit().clear().commit();
        accountId = new AccountsStore(app).addDefault().id;
        Messenger service = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            request = Message.obtain(message);
            return true;
        }));
        shadowOf(app).setComponentNameAndServiceForBindService(
                new ComponentName(app, ProxyProbeService.class), service.getBinder());
    }

    private ActivityController<AccountConfigActivity> open() {
        Intent intent = new Intent(app, AccountConfigActivity.class).putExtra("account_id", accountId);
        return Robolectric.buildActivity(AccountConfigActivity.class, intent).create().start().resume();
    }

    private void reply(boolean ok) throws Exception {
        Message result = Message.obtain(null, ProxyProbeService.RESULT, request.arg1, 0);
        Bundle data = new Bundle();
        data.putBoolean("ok", ok);
        data.putString("error", ok ? null : "unavailable");
        result.setData(data);
        request.replyTo.send(result);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void saveWaitsForProbeBeforePersisting() throws Exception {
        ActivityController<AccountConfigActivity> controller = open();
        AccountConfigActivity activity = controller.get();
        ((EditText) activity.findViewById(R.id.cfgProxy)).setText("localhost:7890");
        activity.findViewById(R.id.cfgSave).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(request);
        assertEquals("", new AccountsStore(app).findById(accountId).proxy);
        assertFalse(activity.findViewById(R.id.cfgProxy).isEnabled());
        assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.cfgProxyProgress).getVisibility());
        assertFalse(activity.findViewById(R.id.cfgSave).isEnabled());
        reply(true);
        assertEquals("http://localhost:7890", new AccountsStore(app).findById(accountId).proxy);
        assertTrue(activity.isFinishing());
        controller.pause().stop().destroy();
    }

    @Test public void failedProbeDoesNotSaveOrFinish() throws Exception {
        ActivityController<AccountConfigActivity> controller = open();
        AccountConfigActivity activity = controller.get();
        ((EditText) activity.findViewById(R.id.cfgProxy)).setText("localhost:7890");
        activity.findViewById(R.id.cfgSave).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        reply(false);
        assertEquals("", new AccountsStore(app).findById(accountId).proxy);
        assertFalse(activity.isFinishing());
        assertTrue(activity.findViewById(R.id.cfgSave).isEnabled());
        assertEquals(android.view.View.GONE, activity.findViewById(R.id.cfgProxyProgress).getVisibility());
        assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.cfgStatusBox).getVisibility());
        controller.pause().stop().destroy();
    }

    @Test public void lateProbeCannotSaveEditedProxy() throws Exception {
        ActivityController<AccountConfigActivity> controller = open();
        AccountConfigActivity activity = controller.get();
        EditText proxy = activity.findViewById(R.id.cfgProxy);
        proxy.setText("localhost:7890");
        activity.findViewById(R.id.cfgSave).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        proxy.setText("localhost:7891");
        reply(true);
        assertEquals("", new AccountsStore(app).findById(accountId).proxy);
        controller.pause().stop().destroy();
    }

    @Test public void leavingConfigurationCancelsPendingSave() throws Exception {
        ActivityController<AccountConfigActivity> controller = open();
        AccountConfigActivity activity = controller.get();
        ((EditText) activity.findViewById(R.id.cfgProxy)).setText("localhost:7890");
        activity.findViewById(R.id.cfgSave).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        controller.pause().stop().destroy();
        reply(true);
        assertEquals("", new AccountsStore(app).findById(accountId).proxy);
    }
}
