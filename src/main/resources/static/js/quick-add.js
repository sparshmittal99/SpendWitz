// Voice input uses the browser's built-in Web Speech API (free, no API key). Chrome, Edge and Safari support it.
(() => {
    const text = document.getElementById('text');
    const micBtn = document.getElementById('micBtn');
    const status = document.getElementById('micStatus');
    const voiceFlag = document.getElementById('voiceFlag');
    const lang = document.getElementById('voiceLang');

    document.querySelectorAll('.example').forEach(btn =>
        btn.addEventListener('click', () => { text.value = btn.textContent; text.focus(); }));

    document.getElementById('quickForm').addEventListener('submit', () =>
        document.getElementById('loading').classList.add('show'));
    window.addEventListener('pageshow', () => document.getElementById('loading').classList.remove('show'));

    const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!Recognition) {
        micBtn.disabled = true;
        lang.disabled = true;
        micBtn.title = 'Voice input needs Chrome, Edge or Safari';
        return;
    }

    let recognition = null;
    let baseText = '';

    const stop = () => {
        if (recognition) recognition.stop();
    };

    micBtn.addEventListener('click', () => {
        if (recognition) {
            stop();
            return;
        }
        recognition = new Recognition();
        recognition.lang = lang.value;
        recognition.interimResults = true;
        recognition.continuous = true;
        baseText = text.value.trim() ? text.value.trim() + ', ' : '';

        recognition.onstart = () => {
            micBtn.classList.add('listening');
            micBtn.setAttribute('aria-label', 'Stop voice input');
            status.textContent = 'Listening… say something like "sabzi 150, chai 20". Tap the mic again to stop.';
        };
        recognition.onresult = event => {
            let transcript = '';
            for (let i = 0; i < event.results.length; i++) {
                transcript += event.results[i][0].transcript;
            }
            text.value = baseText + transcript.trim();
            voiceFlag.value = 'true';
        };
        recognition.onerror = event => {
            status.textContent = event.error === 'not-allowed'
                ? 'Microphone access was blocked. Allow it in your browser settings to use voice.'
                : 'Voice input stopped (' + event.error + '). You can type instead.';
        };
        recognition.onend = () => {
            micBtn.classList.remove('listening');
            micBtn.setAttribute('aria-label', 'Start voice input');
            if (status.textContent.startsWith('Listening')) {
                status.textContent = text.value ? 'Got it. Check the text, then click Extract expenses.' : '';
            }
            recognition = null;
        };
        recognition.start();
    });
})();
