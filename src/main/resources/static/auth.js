/**
 * Everything that knows about the access token.
 *
 * <p>Kept in localStorage rather than a JavaScript variable, deliberately. A token held only
 * in memory disappears on every refresh, and a demo that logs you out each time you reload
 * doesn't get looked at properly. The cost is real though: any XSS on this page can read
 * localStorage and send the token anywhere. What bounds it is that server-sourced strings all
 * go through escapeHtml before touching innerHTML, and tokens expire in thirty minutes.
 * A production deployment would move this to an httpOnly cookie -- unreadable from JavaScript
 * -- and bring back the CSRF protection that a bearer token doesn't need.
 */
const Auth = (function () {
  "use strict";

  const KEY = "aerocore.session";

  let session = read();

  function read() {
    try {
      const raw = localStorage.getItem(KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (error) {
      // Corrupt or unavailable storage shouldn't take the page down; worst case the user
      // signs in again.
      return null;
    }
  }

  function save(response) {
    session = {
      token: response.token,
      email: response.email,
      role:  response.role,
      // The server tells us how long the token lasts. Storing the absolute moment lets the
      // UI show the login screen before a request fails, rather than after.
      expiresAt: Date.now() + response.expiresInSeconds * 1000
    };
    localStorage.setItem(KEY, JSON.stringify(session));
  }

  function clear() {
    session = null;
    localStorage.removeItem(KEY);
  }

  function isSignedIn() {
    if (!session) return false;

    if (Date.now() >= session.expiresAt) {
      // Expiry is checked here as a courtesy, not as security. The server verifies the
      // signature and the expiry claim on every request; this only spares the user a
      // pointless round trip that was always going to fail.
      clear();
      return false;
    }
    return true;
  }

  function authHeader() {
    return isSignedIn() ? { Authorization: "Bearer " + session.token } : {};
  }

  return {
    save,
    clear,
    isSignedIn,
    authHeader,
    email: () => (session ? session.email : null),
    role:  () => (session ? session.role : null)
  };
})();
