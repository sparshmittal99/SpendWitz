// Android app: the camera button in the top bar. Choosing a file opens the app's "Take a photo / Choose from
// gallery / Browse files" sheet; the app compresses photos, then the bills go straight to the scan.
(() => {
    const form = document.getElementById('captureForm');
    const input = document.getElementById('captureFile');
    const loading = document.getElementById('captureLoading');
    if (!form || !input) return;

    const MAX_FILES = 8;
    const MAX_BYTES = 6 * 1024 * 1024;

    input.addEventListener('change', () => {
        const files = [...input.files];
        if (!files.length) return;
        if (files.length > MAX_FILES) {
            alert(`Choose up to ${MAX_FILES} bills at a time.`);
            input.value = '';
            return;
        }
        const big = files.find(f => f.size > MAX_BYTES);
        if (big) {
            alert(`${big.name} is larger than 6 MB.`);
            input.value = '';
            return;
        }
        if (loading) {
            if (files.length > 1) document.getElementById('captureTitle').textContent = `Reading ${files.length} bills…`;
            loading.classList.add('show');
        }
        form.submit();
    });

    // Coming back to this page: hide the spinner and clear the old choice
    window.addEventListener('pageshow', () => {
        if (loading) loading.classList.remove('show');
        input.value = '';
    });
})();
