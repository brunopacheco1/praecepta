// SPDX-FileCopyrightText: Bruno Pacheco
//
// SPDX-License-Identifier: Apache-2.0

// Adds a "Copy" button to each code sample. The page works without it.
document.querySelectorAll('.code pre').forEach((pre) => {
  if (!navigator.clipboard) {
    return;
  }
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'copy';
  button.textContent = 'Copy';
  button.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(pre.innerText);
      button.textContent = 'Copied';
    } catch {
      button.textContent = 'Press Ctrl+C';
    }
    setTimeout(() => { button.textContent = 'Copy'; }, 1600);
  });
  pre.parentElement.append(button);
});
