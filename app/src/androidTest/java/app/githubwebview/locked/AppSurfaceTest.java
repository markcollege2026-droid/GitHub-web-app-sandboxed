package app.githubwebview.locked;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The installed app's attack surface, as the operating system sees it: one exported launcher activity,
 * nothing else, and only the two permissions the WebView needs.
 */
@RunWith(AndroidJUnit4.class)
public class AppSurfaceTest {

    @Test
    public void theAppExposesNothingButItsLauncherActivity() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager packageManager = context.getPackageManager();
        String packageName = context.getPackageName();

        PackageInfo info = packageManager.getPackageInfo(packageName,
                PackageManager.GET_ACTIVITIES
                        | PackageManager.GET_RECEIVERS
                        | PackageManager.GET_SERVICES
                        | PackageManager.GET_PROVIDERS
                        | PackageManager.GET_PERMISSIONS);

        assertNotNull("the app must have exactly one activity", info.activities);
        assertEquals("exactly one activity", 1, info.activities.length);
        ActivityInfo activity = info.activities[0];
        assertTrue(activity.name.endsWith("MainActivity"));
        assertTrue("only the launcher activity may be exported", activity.exported);
        assertEquals("the launcher activity must stay single-top (no task duplication)",
                ActivityInfo.LAUNCH_SINGLE_TOP, activity.launchMode);
        assertEquals("the activity must not be exported through an alias",
                null, activity.targetActivity);

        assertEquals("no broadcast receivers may be exported or registered",
                0, info.receivers == null ? 0 : info.receivers.length);
        ServiceInfo[] services = info.services;
        assertNotNull("the private background download service must be declared", services);
        assertEquals("one foreground download service", 1, services.length);
        assertTrue(services[0].name.endsWith("BackgroundDownloadService"));
        assertFalse("the download service must not be externally callable", services[0].exported);
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                services[0].foregroundServiceType);
        assertEquals("no content providers may exist",
                0, info.providers == null ? 0 : info.providers.length);

        assertNotNull("permissions must be declared explicitly", info.requestedPermissions);
        assertEquals("only network access and foreground-download notification permissions may be requested",
                5, info.requestedPermissions.length);
        for (String permission : info.requestedPermissions) {
            assertTrue("unexpected permission: " + permission,
                    "android.permission.INTERNET".equals(permission)
                            || "android.permission.ACCESS_NETWORK_STATE".equals(permission)
                            || "android.permission.FOREGROUND_SERVICE".equals(permission)
                            || "android.permission.FOREGROUND_SERVICE_DATA_SYNC".equals(permission)
                            || "android.permission.POST_NOTIFICATIONS".equals(permission));
        }
    }
}
