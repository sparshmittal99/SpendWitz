// Android app: link this phone to the signed-in account so over-budget alerts reach it as phone notifications,
// and unlink it on sign-out. The app hands over its Firebase token; nothing happens in a normal browser.
(() => {
    const app = window.SpendWitzApp;
    if (!app || typeof app.pushToken !== 'function') return;

    let token = '';
    try { token = app.pushToken() || ''; } catch (e) { return; }
    const field = document.getElementById('pushTokenField');
    if (field) field.value = token;
    if (!token) return; // alerts off, or Firebase hasn't handed out a token yet (next page will)

    const user = document.currentScript.dataset.user;
    const KEY = 'spendwitz-push';
    const marker = user + '|' + token;
    const read = () => { try { return localStorage.getItem(KEY); } catch (e) { return null; } };
    const write = v => { try { v ? localStorage.setItem(KEY, v) : localStorage.removeItem(KEY); } catch (e) { } };

    if (read() !== marker) {
        const csrf = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        if (csrf && header) headers[header.content] = csrf.content;
        fetch('/app/push-token', { method: 'POST', headers, body: 'token=' + encodeURIComponent(token) })
            .then(r => { if (r.ok) write(marker); })
            .catch(() => { });
    }

    // After signing out, the next sign-in must register the phone again
    const signOut = document.getElementById('signOutForm');
    if (signOut) signOut.addEventListener('submit', () => write(null));
})();
