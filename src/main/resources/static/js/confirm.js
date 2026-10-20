// App-styled confirmation dialog, replacing the browser's confirm().
// Put data-confirm-title (and optionally data-confirm, data-confirm-ok) on a <form> or on its submit button.
(() => {
    const el = document.getElementById('confirmModal');
    if (!el || !window.bootstrap) return;

    const modal = new bootstrap.Modal(el);
    const title = document.getElementById('confirmTitle');
    const message = document.getElementById('confirmMessage');
    const ok = document.getElementById('confirmOk');
    let pending = null;

    document.addEventListener('submit', event => {
        const form = event.target;
        const submitter = event.submitter;
        const trigger = submitter && submitter.dataset.confirmTitle ? submitter
            : form.dataset.confirmTitle ? form : null;
        if (!trigger || form.dataset.confirmed === 'true') return;

        event.preventDefault();
        pending = { form, submitter };
        title.textContent = trigger.dataset.confirmTitle;
        message.textContent = trigger.dataset.confirm || "This can't be undone.";
        ok.textContent = trigger.dataset.confirmOk || 'Delete';
        modal.show();
    }, true);

    ok.addEventListener('click', () => {
        if (!pending) return;
        const { form, submitter } = pending;
        pending = null;
        ok.disabled = true;
        form.dataset.confirmed = 'true';
        form.requestSubmit(submitter && submitter.form === form ? submitter : undefined);
        delete form.dataset.confirmed;
        modal.hide();
    });

    // Destructive action: put focus on Cancel, so pressing Enter doesn't delete by accident
    el.addEventListener('shown.bs.modal', () => el.querySelector('[data-bs-dismiss]').focus());
    el.addEventListener('hidden.bs.modal', () => { pending = null; ok.disabled = false; });
    // Coming back with the browser's Back button: allow confirming again
    window.addEventListener('pageshow', () => {
        document.querySelectorAll('form[data-confirmed]').forEach(f => delete f.dataset.confirmed);
        ok.disabled = false;
    });
})();
