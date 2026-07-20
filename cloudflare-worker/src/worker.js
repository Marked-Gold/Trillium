/**
 * Cloudflare Worker for triplo.club — the Triplo landing / store-redirect domain.
 *
 *   iOS devices     -> App Store listing
 *   Android devices -> Google Play listing
 *   anything else   -> a small landing page with both store links
 *   /privacy        -> the privacy policy
 *   /age-rating     -> content / age suitability breakdown (Apple App Store URL)
 *   /app-ads.txt    -> AdMob publisher declaration (IAB app-ads.txt spec)
 *
 * Deploy with `wrangler deploy` (see README.md).
 */

// The Google Play URL is deterministic from the package name — it is final and
// will resolve as soon as the app is published.
const PLAY_STORE_URL =
  "https://play.google.com/store/apps/details?id=com.allmeatgames.triplo";

// The App Store URL needs the numeric app ID assigned in App Store Connect.
// `6772526976` is the AllMeat Games / Triplo app record (submitted for review 2026-05-23).
const APP_STORE_URL = "https://apps.apple.com/app/id6772526976";

const CONTACT_EMAIL = "mark@allmeatgames.com";
const PRIVACY_UPDATED = "May 17, 2026";
const AGE_RATING_UPDATED = "May 23, 2026";

// IAB app-ads.txt declaration. The crawler hits /app-ads.txt on the developer URL listed in
// each store listing — for Triplo that's triplo.club. One line per ad network the app
// monetizes through; `pub-7742910323184344` is the AllMeat Games AdMob publisher id with
// Google's invariant TAG-ID.
const APP_ADS_TXT =
  "google.com, pub-7742910323184344, DIRECT, f08c47fec0942fa0\n";

const appStoreReady = () => !APP_STORE_URL.includes("<APP_STORE_ID>");

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    if (path === "/privacy") return htmlResponse(privacyPage());
    if (path === "/age-rating") return htmlResponse(ageRatingPage());
    // app-ads.txt must be reachable to every crawler regardless of UA, so it is handled
    // before the platform-based store-redirect logic below.
    if (path === "/app-ads.txt") return textResponse(APP_ADS_TXT);

    const ua = request.headers.get("User-Agent") || "";
    const platform = /iPhone|iPad|iPod/i.test(ua)
      ? "ios"
      : /Android/i.test(ua)
        ? "android"
        : "other";

    // Fire-and-forget marketing event for store redirects — must never delay
    // or fail the redirect itself.
    const trackStoreClick = () =>
      ctx.waitUntil(
        fetch("https://marketing.markrgaskin.workers.dev/e", {
          method: "POST",
          headers: { "Content-Type": "text/plain", "X-Mkt-Key": "triplo" },
          body: JSON.stringify({
            n: "store_click",
            u: request.url,
            mk: url.searchParams.get("mk") ?? undefined,
          }),
        }).catch(() => {}),
      );

    if (platform === "android") {
      trackStoreClick();
      return Response.redirect(PLAY_STORE_URL, 302);
    }
    if (platform === "ios" && appStoreReady()) {
      trackStoreClick();
      return Response.redirect(APP_STORE_URL, 302);
    }

    // Desktop / unknown, or iOS before the App Store id is known.
    return htmlResponse(landingPage());
  },
};

function htmlResponse(body) {
  return new Response(body, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "public, max-age=300",
    },
  });
}

function textResponse(body) {
  return new Response(body, {
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "public, max-age=3600",
    },
  });
}

const STYLE = `
  *{box-sizing:border-box;margin:0;padding:0}
  :root{color-scheme:light}
  body{
    font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
    background:#FDF7F0;color:#2C2A26;line-height:1.65;-webkit-font-smoothing:antialiased;
  }
  a{color:#B5651D}
  .wrap{max-width:640px;margin:0 auto;padding:48px 24px}
  .hero{min-height:100vh;display:flex;flex-direction:column;align-items:center;
    justify-content:center;text-align:center;gap:8px}
  .mark{font-size:64px;line-height:1;color:#B5651D}
  h1{font-size:44px;letter-spacing:-.02em}
  .tag{font-size:18px;color:#6B655C;margin-bottom:28px}
  .stores{display:flex;flex-wrap:wrap;gap:12px;justify-content:center}
  .btn{display:inline-block;padding:14px 22px;border-radius:12px;font-weight:600;
    text-decoration:none;background:#2C2A26;color:#FDF7F0}
  .btn-soon{background:#E4DCCF;color:#8A8276;cursor:default}
  footer{margin-top:36px;font-size:14px;color:#8A8276}
  .doc h1{font-size:30px;margin-bottom:4px}
  .doc h2{font-size:19px;margin:26px 0 6px}
  .doc p,.doc li{margin-bottom:10px}
  .doc ul{padding-left:22px}
  .doc .meta{color:#8A8276;font-size:14px;margin-bottom:8px}
  .doc .back{display:inline-block;margin-top:32px;font-size:14px}
`;

function page(title, inner) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<style>${STYLE}</style>
<script async src="https://marketing.markrgaskin.workers.dev/mkt.js?p=triplo"></script>
</head>
<body>${inner}</body>
</html>`;
}

function landingPage() {
  const appStore = appStoreReady()
    ? `<a class="btn" href="${APP_STORE_URL}">Download on the App Store</a>`
    : `<span class="btn btn-soon">Coming soon to iOS</span>`;
  return page(
    "Triplo — a powers-of-three merge puzzle",
    `<main class="hero">
      <div class="mark">&#9650;</div>
      <h1>Triplo</h1>
      <p class="tag">A powers-of-three merge puzzle.</p>
      <div class="stores">
        <a class="btn" href="${PLAY_STORE_URL}">Get it on Google Play</a>
        ${appStore}
      </div>
      <footer><a href="/privacy">Privacy Policy</a> &middot;
        <a href="/age-rating">Age Rating</a> &middot; AllMeat Games</footer>
    </main>`,
  );
}

function privacyPage() {
  return page(
    "Triplo — Privacy Policy",
    `<article class="wrap doc">
      <h1>Privacy Policy</h1>
      <p class="meta">Triplo &middot; AllMeat Games &middot; Last updated: ${PRIVACY_UPDATED}</p>

      <p>Triplo (&ldquo;the app&rdquo;) is a puzzle game published by AllMeat Games. This policy
      explains what information is involved when you use Triplo and how it is handled.</p>

      <h2>Information the app collects</h2>
      <p>Triplo does not require an account and does not ask you for personal information. Your
      game progress and high scores are stored locally on your device and are not transmitted
      to us. We do not operate servers that collect or store your personal data.</p>

      <h2>Advertising</h2>
      <p>Triplo shows interstitial ads through <strong>Google AdMob</strong>. To deliver and
      measure ads, the Google Mobile Ads SDK may collect and process:</p>
      <ul>
        <li>a device or advertising identifier;</li>
        <li>IP address and the approximate location derived from it;</li>
        <li>device information such as model, operating system and language;</li>
        <li>ad-interaction and app-usage data.</li>
      </ul>
      <p>This data is handled by Google under the
      <a href="https://policies.google.com/privacy">Google Privacy Policy</a> and
      <a href="https://policies.google.com/technologies/ads">Google&rsquo;s advertising
      policies</a>.</p>

      <h2>Consent</h2>
      <p>If you are in the European Economic Area, the United Kingdom, or another region with
      applicable privacy laws, Triplo shows a consent prompt (via Google&rsquo;s User Messaging
      Platform) before ads are personalized. You can change or withdraw your consent through
      your device&rsquo;s ad settings.</p>

      <h2>Your choices</h2>
      <ul>
        <li><strong>Android:</strong> Settings &rarr; Privacy &rarr; Ads &mdash; reset or delete
        your advertising ID and opt out of ad personalization.</li>
        <li><strong>iOS:</strong> Settings &rarr; Privacy &amp; Security &rarr; Tracking, and
        Settings &rarr; Privacy &amp; Security &rarr; Apple Advertising.</li>
      </ul>

      <h2>Children&rsquo;s privacy</h2>
      <p>Triplo is intended for a general audience and is not directed at children under 13. We
      do not knowingly collect personal information from children.</p>

      <h2>Changes to this policy</h2>
      <p>We may update this policy from time to time. Material changes are reflected by updating
      the &ldquo;Last updated&rdquo; date above.</p>

      <h2>Contact</h2>
      <p>Questions about this policy can be sent to
      <a href="mailto:${CONTACT_EMAIL}">${CONTACT_EMAIL}</a>.</p>

      <a class="back" href="/">&larr; Back to Triplo</a>
    </article>`,
  );
}

function ageRatingPage() {
  return page(
    "Triplo — Age Rating & Content",
    `<article class="wrap doc">
      <h1>Age Rating &amp; Content</h1>
      <p class="meta">Triplo &middot; AllMeat Games &middot; Last updated: ${AGE_RATING_UPDATED}</p>

      <p>Triplo is a calm, family-friendly merge puzzle game designed for a general audience.
      This page describes what the app contains so parents, guardians, and players can make
      an informed decision before downloading.</p>

      <h2>What&rsquo;s in the game</h2>
      <ul>
        <li><strong>Gameplay:</strong> tap groups of three matching tiles on a 7&times;7 grid to
        merge them into the next power of three.</li>
        <li><strong>Power-ups:</strong> a cartoon bomb icon clears a small area of tiles; a
        cartoon rocket icon clears a single row. They are abstract gameplay tools &mdash; no
        characters are depicted, and there is no violence, injury, or conflict between people.</li>
        <li><strong>Visuals:</strong> simple geometric vector art with a calm, slowly shifting
        background.</li>
        <li><strong>Audio:</strong> optional sound effects and haptic feedback only. No voice,
        no music with lyrics.</li>
      </ul>

      <h2>What the game does <em>not</em> contain</h2>
      <ul>
        <li>No realistic violence, blood, or weapons used against people or creatures.</li>
        <li>No profanity, crude humor, or mature themes.</li>
        <li>No sexual content or nudity.</li>
        <li>No depictions or references to alcohol, tobacco, or drug use.</li>
        <li>No gambling, simulated gambling, or wagering of any kind.</li>
        <li>No horror or fear themes.</li>
        <li>No user-generated content, chat, messaging, or social networking features.</li>
        <li>No accounts, sign-ins, or personal information collection.</li>
        <li>No in-app purchases.</li>
      </ul>

      <h2>Advertising</h2>
      <p>Triplo shows interstitial ads through Google AdMob between games. Ad content is served
      by Google and filtered to the &ldquo;general audiences&rdquo; category. On iOS, the App
      Tracking Transparency prompt is shown before any tracking takes place. See the
      <a href="/privacy">Privacy Policy</a> for the full data-handling breakdown.</p>

      <h2>Recommended age</h2>
      <p>The mechanics are simple enough for young children to play, but the powers-of-three
      arithmetic and the strategic use of limited power-ups are most rewarding for ages 7 and
      up. Younger players can comfortably play with adult guidance.</p>

      <h2>Questions</h2>
      <p>Comments or concerns about the app&rsquo;s content can be sent to
      <a href="mailto:${CONTACT_EMAIL}">${CONTACT_EMAIL}</a>.</p>

      <a class="back" href="/">&larr; Back to Triplo</a>
    </article>`,
  );
}
