// Streams the assistant's answer as NDJSON ({"text": "..."} per line) and renders Markdown as it arrives.
(() => {
    const messages = document.getElementById('messages');
    const form = document.getElementById('chatForm');
    const input = document.getElementById('chatInput');
    const sendBtn = document.getElementById('sendBtn');
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
    let busy = false;

    const headers = extra => {
        const h = { ...extra };
        if (csrfToken && csrfHeader) h[csrfHeader] = csrfToken;
        return h;
    };

    const scrollDown = () => { messages.scrollTop = messages.scrollHeight; };

    const addMessage = (cls, html) => {
        const div = document.createElement('div');
        div.className = `msg ${cls}`;
        div.innerHTML = html;
        messages.appendChild(div);
        scrollDown();
        return div;
    };

    const renderMarkdown = text => DOMPurify.sanitize(marked.parse(text));
    const escapeHtml = text => {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    };

    async function ask(question) {
        if (busy || !question.trim()) return;
        busy = true;
        sendBtn.disabled = true;
        addMessage('msg-user', escapeHtml(question));
        const bubble = addMessage('msg-ai', '<span class="typing"><span></span><span></span><span></span></span>');

        let answer = '';
        try {
            const res = await fetch('/assistant/chat', {
                method: 'POST',
                headers: headers({ 'Content-Type': 'application/json', 'Accept': 'application/x-ndjson' }),
                body: JSON.stringify({ message: question })
            });
            if (res.status === 401 || res.redirected) {
                window.location = '/login';
                return;
            }
            if (!res.ok) throw new Error(`HTTP ${res.status}`);

            const reader = res.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';
            for (;;) {
                const { value, done } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop();
                for (const line of lines) {
                    if (!line.trim()) continue;
                    const chunk = JSON.parse(line);
                    if (chunk.error) {
                        bubble.className = 'msg msg-error';
                        bubble.textContent = chunk.error;
                        return;
                    }
                    answer += chunk.text || '';
                    bubble.innerHTML = renderMarkdown(answer);
                    scrollDown();
                }
            }
            if (!answer) {
                bubble.className = 'msg msg-error';
                bubble.textContent = 'No answer came back. Please try again.';
            }
        } catch (e) {
            bubble.className = 'msg msg-error';
            bubble.textContent = 'Something went wrong. Please try again.';
        } finally {
            busy = false;
            sendBtn.disabled = false;
            input.focus();
        }
    }

    form.addEventListener('submit', e => {
        e.preventDefault();
        const q = input.value;
        input.value = '';
        ask(q);
    });

    messages.addEventListener('click', e => {
        const btn = e.target.closest('.suggestion');
        if (btn) ask(btn.textContent);
    });

    document.getElementById('resetBtn').addEventListener('click', async () => {
        await fetch('/assistant/reset', { method: 'POST', headers: headers({}) });
        messages.querySelectorAll('.msg:not(#welcome)').forEach(m => m.remove());
        input.focus();
    });
})();
