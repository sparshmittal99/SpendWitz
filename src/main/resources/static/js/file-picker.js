// Multi-file picker for the upload pages: #dropzone, #file, #fileList and #fileError.
// Lists the chosen files with a remove button and checks the count and size before upload.
function filePicker({ max, maxBytes, button, label }) {
    const input = document.getElementById('file');
    const dropzone = document.getElementById('dropzone');
    const list = document.getElementById('fileList');
    const error = document.getElementById('fileError');

    const sizeText = bytes => bytes < 1024 * 1024
        ? Math.max(1, Math.round(bytes / 1024)) + ' KB'
        : (bytes / 1024 / 1024).toFixed(1) + ' MB';

    const setFiles = files => {
        const dt = new DataTransfer();
        files.forEach(f => dt.items.add(f));
        input.files = dt.files;
        render();
    };

    const render = () => {
        const files = [...input.files];
        list.replaceChildren(...files.map((f, i) => {
            const li = document.createElement('li');
            const icon = document.createElement('i');
            icon.className = 'bi ' + (f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf')
                ? 'bi-file-earmark-pdf' : /\.(xlsx?|csv)$/i.test(f.name) ? 'bi-file-earmark-spreadsheet' : 'bi-file-earmark-image');
            const name = document.createElement('span');
            name.className = 'file-name';
            name.textContent = f.name;
            const size = document.createElement('span');
            size.className = 'file-size';
            size.textContent = sizeText(f.size);
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'btn btn-sm btn-link text-secondary p-0';
            remove.setAttribute('aria-label', 'Remove ' + f.name);
            remove.innerHTML = '<i class="bi bi-x-lg"></i>';
            remove.addEventListener('click', () => setFiles(files.filter((_, j) => j !== i)));
            li.append(icon, name, size, remove);
            return li;
        }));

        let problem = '';
        if (files.length > max) problem = `Choose up to ${max} files at a time.`;
        const big = files.find(f => f.size > maxBytes);
        if (big) problem = `${big.name} is larger than ${sizeText(maxBytes)}.`;
        error.textContent = problem;
        error.classList.toggle('d-none', !problem);
        button.disabled = files.length === 0 || !!problem;
        if (label) button.lastChild.textContent = ' ' + label(files.length);
    };

    input.addEventListener('change', render);
    ['dragenter', 'dragover'].forEach(ev => dropzone.addEventListener(ev, e => { e.preventDefault(); dropzone.classList.add('dragover'); }));
    ['dragleave', 'drop'].forEach(ev => dropzone.addEventListener(ev, e => { e.preventDefault(); dropzone.classList.remove('dragover'); }));
    // Dropping adds to what's already chosen, so files can be gathered from several folders
    dropzone.addEventListener('drop', e => setFiles([...input.files, ...e.dataTransfer.files]));
    render();
}
