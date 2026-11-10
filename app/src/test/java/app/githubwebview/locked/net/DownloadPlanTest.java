package app.githubwebview.locked.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.githubwebview.locked.policy.BlockReason;
import app.githubwebview.locked.policy.GitHubAllowlist;
import app.githubwebview.locked.policy.PolicyConfig;

/**
 * The rules that decide whether a download may start, and what it is called.
 *
 * <p>Pure JVM: {@link DownloadPlan} has no Android dependency, so every hostile input - a traversal name,
 * a CR/LF header, an overlong name, a redirect to an unapproved host, a 1 GiB-and-one-byte file - can be
 * checked here without an emulator.</p>
 */
public class DownloadPlanTest {

    private final PolicyConfig policy = GitHubAllowlist.productionConfig();

    private DownloadPlan.Plan plan(String url) {
        return DownloadPlan.plan(url, null, null, policy);
    }

    // --- the policy question ------------------------------------------------------------------

    @Test
    public void theHostsGitHubActuallyDownloadsFromAreAllowed() {
        String[] urls = {
                // "Download raw file" / blob raw link
                "https://raw.githubusercontent.com/o/r/main/README.md",
                // "Download ZIP" for a branch and for a tag
                "https://codeload.github.com/o/r/zip/refs/heads/main",
                "https://codeload.github.com/o/r/tar.gz/refs/tags/v1.0.0",
                // a release asset
                "https://objects.githubusercontent.com/github-production-release-asset-2e65be/1/2?X-Amz-Signature=x",
                // the release-asset origin and the documented legacy bucket
                "https://release-assets.githubusercontent.com/x/y.zip",
                "https://github-cloud.s3.amazonaws.com/x/y.zip",
                // GitHub Actions artifact downloads redirect to results storage hosts. Every
                // numeric endpoint, including future ones, must be downloadable as a resource.
                "https://productionresultssa0.blob.core.windows.net/actions-results/artifact.zip?sig=x",
                "https://productionresultssa19.blob.core.windows.net/actions-results/artifact.zip?sig=x",
                "https://productionresultssa30.blob.core.windows.net/actions-results/artifact.zip?sig=x",
                "https://productionresultssa49.blob.core.windows.net/actions-results/artifact.zip?sig=x",
                "https://productionresultssa100.blob.core.windows.net/actions-results/artifact.zip?sig=x",
                "https://productionresultssa199.blob.core.windows.net/actions-results/artifact.zip?sig=x",
                "https://productionresultssa200.blob.core.windows.net/actions-results/artifact.zip?sig=x",
        };
        for (String url : urls) {
            DownloadPlan.Plan plan = plan(url);
            assertTrue("should be downloadable: " + url + " (" + plan.refusal() + ")", plan.isAllowed());
            assertFalse("a name must be derived", plan.fileName().isEmpty());
        }
    }

    @Test
    public void everythingElseIsRefusedWithThePolicyReason() {
        DownloadPlan.Plan evil = plan("https://evil.example/payload.apk");
        assertFalse(evil.isAllowed());
        assertEquals(DownloadPlan.Refusal.NOT_ALLOWED, evil.refusal());
        assertEquals(BlockReason.HOST_NOT_ALLOWED, evil.reason());
        assertEquals("evil.example", evil.host());

        // A look-alike host is not "GitHub with a prefix".
        assertFalse(plan("https://github.com.evil.example/x.zip").isAllowed());
        assertFalse(plan("https://docs.github.com/x.zip").isAllowed());
        assertFalse(plan("https://raw.githubusercontent.com.evil.example/x").isAllowed());
        // Results storage look-alikes are not downloads either.
        assertFalse(plan("https://productionresultssa30.blob.core.windows.net.evil.example/x.zip").isAllowed());
        assertFalse(plan("https://evilproductionresultssa30.blob.core.windows.net/x.zip").isAllowed());
        assertFalse(plan("https://productionresultssaX.blob.core.windows.net/x.zip").isAllowed());
        assertFalse(plan("https://unrelated.blob.core.windows.net/x.zip").isAllowed());
    }

    @Test
    public void cleartextAndOtherSchemesAreRefused() {
        DownloadPlan.Plan http = plan("http://raw.githubusercontent.com/o/r/main/x.zip");
        assertFalse(http.isAllowed());
        assertEquals(DownloadPlan.Refusal.NOT_HTTPS, http.refusal());
        assertEquals(BlockReason.SCHEME_NOT_HTTPS, http.reason());

        for (String url : new String[]{
                "ftp://raw.githubusercontent.com/x",
                "file:///sdcard/x.apk",
                "content://com.android.providers.downloads/x",
                "javascript:alert(1)",
                "intent://x#Intent;end"}) {
            assertFalse("must be refused: " + url, plan(url).isAllowed());
        }
    }

    @Test
    public void linksMintedInsideThePageAreRefusedWithAnExplanation() {
        for (String url : new String[]{
                "blob:https://github.com/1a2b3c4d",
                "data:text/plain;base64,aGk=",
                "about:blank"}) {
            DownloadPlan.Plan plan = plan(url);
            assertFalse("must be refused: " + url, plan.isAllowed());
            assertEquals(DownloadPlan.Refusal.IN_PAGE_URL, plan.refusal());
            assertTrue("the user must be told why", plan.describe().contains("inside the page"));
        }
    }

    @Test
    public void emptyOrMissingUrlsAreRefused() {
        assertEquals(DownloadPlan.Refusal.MISSING_URL, DownloadPlan.plan(null, null, null, policy).refusal());
        assertEquals(DownloadPlan.Refusal.MISSING_URL, DownloadPlan.plan("   ", null, null, policy).refusal());
        assertEquals(DownloadPlan.Refusal.NOT_ALLOWED, DownloadPlan.plan(
                "https://raw.githubusercontent.com/o/r/main/x", null, null, null).refusal());
    }

    @Test
    public void theRefusalCarriesTheReasonTheOverlayShows() {
        DownloadPlan.Plan plan = plan("https://support.github.com/x.zip");
        assertFalse(plan.isAllowed());
        assertNotNull(plan.reason());
        assertTrue("a human sentence is required", plan.describe().length() > 10);
        assertEquals("the refusal must name the host", "support.github.com", plan.host());
    }

    // --- size ---------------------------------------------------------------------------------

    @Test
    public void theSizeCeilingIsHard() {
        assertFalse(DownloadPlan.tooLarge(-1L)); // unknown length is not "too large"
        assertFalse(DownloadPlan.tooLarge(0L));
        assertFalse(DownloadPlan.tooLarge(DownloadPlan.MAX_BYTES));
        assertTrue(DownloadPlan.tooLarge(DownloadPlan.MAX_BYTES + 1L));
        assertEquals(1024L * 1024L * 1024L, DownloadPlan.MAX_BYTES);
    }

    @Test
    public void anOversizedPlanIsRefusedWithTheSizeReason() {
        DownloadPlan.Plan allowed = plan("https://codeload.github.com/o/r/zip/refs/heads/main");
        DownloadPlan.Plan refused = DownloadPlan.tooLarge(allowed);
        assertFalse(refused.isAllowed());
        assertEquals(DownloadPlan.Refusal.TOO_LARGE, refused.refusal());
        assertTrue(refused.describe().contains("1024 MB"));
        assertEquals("the name survives the refusal", allowed.fileName(), refused.fileName());
    }

    // --- names --------------------------------------------------------------------------------

    @Test
    public void aNameFromTheHeaderBeatsTheUrl() {
        assertEquals("report.pdf", DownloadPlan.fileName(
                "attachment; filename=\"report.pdf\"", "https://objects.githubusercontent.com/a/b?c=d", null));
        // RFC 5987 form: percent-decoded, still sanitised.
        assertEquals("my archive.zip", DownloadPlan.fileName(
                "attachment; filename*=UTF-8''my%20archive.zip",
                "https://objects.githubusercontent.com/a/b", null));
    }

    @Test
    public void aHeaderNameIsSanitisedToo() {
        assertEquals("passwd", DownloadPlan.fileName("attachment; filename=\"../../etc/passwd\"",
                "https://raw.githubusercontent.com/o/r/main/x", null));
        assertFalse(DownloadPlan.fileName("attachment; filename=\"..\\..\\win.ini\"",
                "https://raw.githubusercontent.com/o/r/main/x", null).contains("\\"));
    }

    @Test
    public void traversalCannotEscapeTheDownloadsFolder() {
        assertEquals("passwd", DownloadPlan.sanitize("../../etc/passwd"));
        assertEquals("win.ini", DownloadPlan.sanitize("..\\..\\windows\\win.ini"));
        assertEquals("x.zip", DownloadPlan.sanitize("/absolute/x.zip"));
        assertEquals("", DownloadPlan.sanitize(".."));
        assertEquals("", DownloadPlan.sanitize("."));
        assertEquals("", DownloadPlan.sanitize("..."));
        assertEquals("", DownloadPlan.sanitize(null));
        for (String hostile : new String[]{"../../x", "..\\..\\x", "a/../../b", "/etc/shadow"}) {
            String clean = DownloadPlan.sanitize(hostile);
            assertFalse("must not contain a separator: " + clean,
                    clean.indexOf('/') >= 0 || clean.indexOf('\\') >= 0);
        }
    }

    @Test
    public void controlCharactersCannotSurviveIntoAHeaderOrName() {
        String clean = DownloadPlan.sanitize("a\r\nSet-Cookie: evil=1");
        assertFalse(clean.indexOf('\r') >= 0);
        assertFalse(clean.indexOf('\n') >= 0);
        assertFalse("':' is reserved on removable media", clean.indexOf(':') >= 0);
        assertEquals("areport.pdf", DownloadPlan.sanitize("a\u0000report.pdf"));
    }

    @Test
    public void reservedAndHiddenNamesAreMadeSafe() {
        assertEquals("a_b_c_d_e_f_g", DownloadPlan.sanitize("a<b>c:d\"e|f?g"));
        assertFalse(DownloadPlan.sanitize(".hidden").startsWith("."));
        assertEquals("report.pdf", DownloadPlan.sanitize("report.pdf."));
        assertEquals("name.txt", DownloadPlan.sanitize("  name.txt  "));
    }

    @Test
    public void longNamesAreTruncatedButKeepTheirExtension() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            huge.append('a');
        }
        String name = DownloadPlan.sanitize(huge + ".tar.gz");
        assertTrue("must stay within the limit: " + name.length(), name.length() <= DownloadPlan.MAX_NAME_LENGTH);
        assertTrue("the extension must survive", name.endsWith(".tar.gz"));
    }

    @Test
    public void anUnusableNameFallsBackToTheMimeType() {
        // The URL's last segment is "r", so the MIME-derived extension is appended to it.
        assertEquals("r.zip", DownloadPlan.fileName(null,
                "https://codeload.github.com/o/r", "application/zip"));
        assertEquals("main.zip", DownloadPlan.fileName(null,
                "https://codeload.github.com/o/r/zip/refs/heads/main", "application/zip"));
        // Nothing usable anywhere: the fixed fallback is used, never an empty name.
        assertEquals(DownloadPlan.FALLBACK_NAME, DownloadPlan.fileName(null, "https://codeload.github.com", null));
    }

    @Test
    public void theMimeTypeIsNormalisedAndNeverTrustedBeyondItsShape() {
        assertEquals("text/plain", DownloadPlan.mimeType("text/plain; charset=utf-8", null));
        assertEquals("application/zip", DownloadPlan.mimeType(null, "application/zip"));
        assertEquals("application/octet-stream", DownloadPlan.mimeType("garbage", null));
        assertEquals("text/csv", DownloadPlan.mimeType("", "text/csv"));
        assertEquals("application/octet-stream", DownloadPlan.mimeType(null, null));
    }

    @Test
    public void redirectsAreRecognised() {
        for (int code : new int[]{301, 302, 303, 307, 308}) {
            assertTrue("redirect: " + code, DownloadPlan.isRedirect(code));
        }
        for (int code : new int[]{200, 204, 304, 400, 403, 404, 500}) {
            assertFalse("not a redirect: " + code, DownloadPlan.isRedirect(code));
        }
        assertTrue("the redirect budget must stay bounded", DownloadPlan.MAX_REDIRECTS <= 10);
    }
}
