/* Sistema de Gestión Comercial - comportamiento común de las páginas */
(function () {
    'use strict';

    // Evita registrar dos veces el mismo documento por un doble clic.
    document.querySelectorAll('form[data-un-envio]').forEach(function (form) {
        form.addEventListener('submit', function (e) {
            if (e.defaultPrevented) return;
            if (form.dataset.enviado) {
                e.preventDefault();
                return;
            }
            form.dataset.enviado = '1';
            form.querySelectorAll('button[type=submit]').forEach(function (b) { b.disabled = true; });
        });
    });

    // Reportes: al cambiar de reporte se genera de inmediato.
    document.querySelectorAll('select[data-auto-enviar]').forEach(function (s) {
        s.addEventListener('change', function () { s.form.submit(); });
    });

    // Productos: "controla vencimiento" requiere "controla lotes".
    var lote = document.getElementById('controlaLote');
    var vence = document.getElementById('controlaVencimiento');
    if (lote && vence) {
        vence.addEventListener('change', function () { if (vence.checked) lote.checked = true; });
        lote.addEventListener('change', function () { if (!lote.checked) vence.checked = false; });
    }

    // Reportes: ventana para anular un documento.
    var modal = document.getElementById('modalAnular');
    if (modal) {
        modal.addEventListener('show.bs.modal', function (e) {
            var b = e.relatedTarget;
            document.getElementById('anularDocumento').value = b.dataset.documento;
            document.getElementById('anularNumero').textContent = b.dataset.documento;
            document.getElementById('anularTipo').textContent = b.dataset.tipo;
            document.getElementById('motivo').value = '';
        });
        modal.addEventListener('shown.bs.modal', function () {
            document.getElementById('motivo').focus();
        });
    }
})();
