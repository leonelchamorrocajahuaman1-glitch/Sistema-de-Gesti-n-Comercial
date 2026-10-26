/*
 * Pantalla de ventas, compras y transferencias.
 * Las líneas se arman en el navegador y se envían juntas al pulsar "Registrar";
 * el servidor (PostgreSQL) hace la validación definitiva de stock y lotes.
 */
(function () {
    'use strict';

    var raiz = document.getElementById('movimiento');
    if (!raiz) return;
    var modo = raiz.dataset.modo;            // ENTRADA, SALIDA o TRANSFERENCIA
    var api = raiz.dataset.api;
    var conImporte = modo !== 'TRANSFERENCIA';

    var $ = function (id) { return document.getElementById(id); };
    var form = $('formDocumento');
    var tipo = $('tipo');
    var almacen = $('almacen');
    var producto = $('nProducto');
    var cantidad = $('nCantidad');
    var importe = $('nImporte');
    var lote = $('nLote');
    var vence = $('nVence');
    var info = $('infoProducto');
    var errorLinea = $('errorLinea');
    var cuerpo = document.querySelector('#tablaLineas tbody');
    var sinLineas = $('sinLineas');
    var CAMPOS = ['producto', 'descripcion', 'cantidad', 'importe', 'lote', 'loteTexto', 'vencimiento'];

    var numero = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    var cantidadFmt = new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 });

    function esVenta() {
        return modo === 'SALIDA' && tipo && tipo.value === 'VENTA';
    }

    function lineas() {
        return Array.prototype.slice.call(cuerpo.querySelectorAll('tr.linea'));
    }

    // Da a cada línea los nombres lineas[0].producto, lineas[1].producto... y recalcula el total.
    function renumerar() {
        var total = 0;
        lineas().forEach(function (tr, i) {
            tr.querySelectorAll('input[data-campo]').forEach(function (inp) {
                inp.name = 'lineas[' + i + '].' + inp.dataset.campo;
            });
            var cant = parseFloat(tr.querySelector('[data-campo=cantidad]').value) || 0;
            var unit = parseFloat(tr.querySelector('[data-campo=importe]').value);
            var celda = tr.querySelector('.importe');
            if (celda) {
                var imp = isNaN(unit) ? null : Math.round(cant * unit * 100) / 100;
                celda.textContent = imp === null ? '' : numero.format(imp);
                if (imp !== null) total += imp;
            }
        });
        sinLineas.hidden = lineas().length > 0;
        if ($('total')) $('total').textContent = numero.format(total);
    }

    function td(texto, clase) {
        var c = document.createElement('td');
        c.textContent = texto;          // textContent: nunca se interpreta como HTML
        if (clase) c.className = clase;
        return c;
    }

    function agregar() {
        errorLinea.textContent = '';
        var opcion = producto.options[producto.selectedIndex];
        if (!producto.value) { errorLinea.textContent = 'Seleccione un producto.'; producto.focus(); return; }
        var cant = parseFloat(cantidad.value);
        if (!(cant > 0)) { errorLinea.textContent = 'Ingrese una cantidad mayor que cero.'; cantidad.focus(); return; }
        var unit = importe && !importe.disabled && importe.value !== '' ? parseFloat(importe.value) : null;
        if (modo === 'ENTRADA' && (unit === null || unit < 0)) {
            errorLinea.textContent = 'Ingrese el costo unitario.'; importe.focus(); return;
        }
        if (unit !== null && unit < 0) { errorLinea.textContent = 'El importe no puede ser negativo.'; return; }

        var partes = opcion.text.split(' - ');
        var descripcion = partes.length > 1 ? partes.slice(1).join(' - ') : '';
        var valorLote = lote ? lote.value.trim() : '';
        var textoLote = lote && lote.tagName === 'SELECT' ? (valorLote ? lote.options[lote.selectedIndex].text : '') : valorLote;
        var datos = {
            producto: producto.value, descripcion: descripcion, cantidad: String(cant),
            importe: unit === null ? '' : String(unit), lote: valorLote, loteTexto: textoLote,
            vencimiento: vence ? vence.value : ''
        };

        var tr = document.createElement('tr');
        tr.className = 'linea';
        tr.appendChild(td(datos.producto, 'fw-semibold'));
        tr.appendChild(td(datos.descripcion));
        tr.appendChild(td(cantidadFmt.format(cant), 'text-end'));
        if (conImporte) tr.appendChild(td(unit === null ? '' : numero.format(unit), 'text-end'));
        tr.appendChild(td(valorLote ? textoLote : (modo === 'ENTRADA' ? '' : 'Automático')));
        if (modo === 'ENTRADA') tr.appendChild(td(datos.vencimiento));
        if (conImporte) tr.appendChild(td('', 'text-end importe'));
        var acciones = td('', 'text-end');
        var quitar = document.createElement('button');
        quitar.type = 'button';
        quitar.className = 'btn btn-outline-danger btn-sm quitar';
        quitar.setAttribute('aria-label', 'Quitar línea');
        quitar.innerHTML = '<i class="bi bi-trash"></i>';
        acciones.appendChild(quitar);
        CAMPOS.forEach(function (campo) {
            var inp = document.createElement('input');
            inp.type = 'hidden';
            inp.dataset.campo = campo;
            inp.value = datos[campo];
            acciones.appendChild(inp);
        });
        tr.appendChild(acciones);
        cuerpo.insertBefore(tr, sinLineas);
        renumerar();

        cantidad.value = '';
        if (modo === 'ENTRADA') { importe.value = ''; lote.value = ''; vence.value = ''; }
        cantidad.focus();
    }

    // Consulta stock, precio y lotes del producto en el almacén elegido.
    function actualizarProducto() {
        info.textContent = '';
        if (lote && lote.tagName === 'SELECT') {
            lote.length = 1;  // deja solo "Automático"
        }
        if (!producto.value || !almacen.value) return;
        var url = api + '?almacen=' + encodeURIComponent(almacen.value) + '&producto=' + encodeURIComponent(producto.value);
        fetch(url, { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
            .then(function (r) {
                if (r.redirected || !r.ok) throw new Error('sesion');
                return r.json();
            })
            .then(function (d) {
                var nombreAlmacen = almacen.options[almacen.selectedIndex].text;
                var texto = 'Stock disponible en ' + nombreAlmacen + ': ' + cantidadFmt.format(d.stock)
                    + '   ·   Costo promedio: S/ ' + numero.format(d.costo);
                if (modo === 'SALIDA') texto += '   ·   Precio de venta: S/ ' + numero.format(d.precio);
                info.textContent = texto;
                if (modo === 'SALIDA' && importe) importe.value = Number(d.precio).toFixed(2);
                if (modo === 'ENTRADA' && importe && importe.value === '' && d.costo > 0) {
                    importe.value = Number(d.costo).toFixed(2);
                }
                if (lote && lote.tagName === 'SELECT') {
                    d.lotes.forEach(function (l) {
                        var o = document.createElement('option');
                        o.value = l.codigo;
                        o.textContent = l.texto;
                        lote.appendChild(o);
                    });
                }
            })
            .catch(function () {
                info.textContent = 'No se pudo consultar el stock. Si su sesión venció, recargue la página.';
            });
    }

    function actualizarTipo() {
        if (importe && modo === 'SALIDA') {
            importe.disabled = !esVenta();
            if (!esVenta()) importe.value = '';
            else actualizarProducto();
        }
    }

    $('btnAgregar').addEventListener('click', agregar);
    // Enter en los campos de "Agregar producto" agrega la línea (no envía el documento).
    $('sinEnvio').addEventListener('submit', function (e) { e.preventDefault(); agregar(); });
    [cantidad, importe, lote, vence].forEach(function (campo) {
        if (campo && campo.tagName === 'INPUT') {
            campo.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') { e.preventDefault(); agregar(); }
            });
        }
    });
    // Enter en "Referencia" no registra el documento por accidente.
    $('referencia').addEventListener('keydown', function (e) {
        if (e.key === 'Enter') e.preventDefault();
    });
    producto.addEventListener('change', actualizarProducto);
    almacen.addEventListener('change', actualizarProducto);
    if (tipo) tipo.addEventListener('change', actualizarTipo);

    cuerpo.addEventListener('click', function (e) {
        var b = e.target.closest('.quitar');
        if (b) {
            b.closest('tr').remove();
            renumerar();
        }
    });

    $('btnLimpiar').addEventListener('click', function () {
        lineas().forEach(function (tr) { tr.remove(); });
        $('referencia').value = '';
        errorLinea.textContent = '';
        renumerar();
    });

    form.addEventListener('submit', function (e) {
        if (lineas().length === 0) {
            e.preventDefault();
            errorLinea.textContent = 'Agregue al menos un producto antes de registrar.';
            producto.focus();
            return;
        }
        var destino = $('destino');
        if (destino && destino.value === almacen.value) {
            e.preventDefault();
            errorLinea.textContent = 'El almacén de origen y el de destino deben ser distintos.';
        }
    }, true);  // fase de captura: se ejecuta antes del bloqueo de doble envío de app.js

    actualizarTipo();
    renumerar();
})();
