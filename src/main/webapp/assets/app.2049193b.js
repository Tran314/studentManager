document.querySelectorAll("form[data-confirm]").forEach((form) => {
    form.addEventListener("submit", (event) => {
        if (!window.confirm(form.dataset.confirm)) event.preventDefault();
    });
});
document.querySelectorAll("nav a").forEach((link) => {
    if (new URL(link.href).pathname === window.location.pathname) {
        link.classList.add("active");
        link.setAttribute("aria-current", "page");
    }
});
