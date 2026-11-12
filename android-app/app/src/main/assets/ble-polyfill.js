// EX30 Batarya Android uygulaması: navigator.bluetooth'u Android BLE köprüsüne bağlar.
(function () {
  if (!window.AndroidBle) return;
  window.EX30_APP = true;
  var pend = {}, seq = 0, devices = {};
  function call(fn) {
    var args = Array.prototype.slice.call(arguments, 1);
    return new Promise(function (res, rej) {
      var id = 'c' + (++seq);
      pend[id] = { res: res, rej: rej };
      try { AndroidBle[fn].apply(AndroidBle, args.concat([id])); }
      catch (e) { delete pend[id]; rej(e); }
    });
  }
  function err(name, msg) { var e = new Error(msg); e.name = name; return e; }
  function norm(u) {
    if (typeof u === 'number') return '0000' + u.toString(16).padStart(4, '0') + '-0000-1000-8000-00805f9b34fb';
    return String(u).toLowerCase();
  }
  function toB64(d) {
    var u = d instanceof ArrayBuffer ? new Uint8Array(d) : new Uint8Array(d.buffer, d.byteOffset, d.byteLength);
    var s = ''; for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
    return btoa(s);
  }
  window.__ble = {
    _resolve: function (id, v) { var p = pend[id]; if (p) { delete pend[id]; p.res(v); } },
    _reject: function (id, name, msg) { var p = pend[id]; if (p) { delete pend[id]; p.rej(err(name, msg)); } },
    _notify: function (devId, svc, chr, b64) {
      var d = devices[devId]; if (!d) return;
      var c = d._chars[(svc + '|' + chr).toLowerCase()]; if (!c) return;
      var bin = atob(b64), u = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
      c.value = new DataView(u.buffer);
      c._ls.slice().forEach(function (f) { try { f({ target: c, type: 'characteristicvaluechanged' }); } catch (e) { console.error(e); } });
    },
    _disconnected: function (devId) {
      var d = devices[devId]; if (!d || !d.gatt.connected) return;
      d.gatt.connected = false;
      d._ls.slice().forEach(function (f) { try { f({ target: d, type: 'gattserverdisconnected' }); } catch (e) { console.error(e); } });
    }
  };
  function mkChar(dev, svc, c) {
    var key = svc.uuid + '|' + c.uuid;
    if (dev._chars[key]) return dev._chars[key];
    var ch = {
      uuid: c.uuid, service: svc, properties: c.props, value: null, _ls: [],
      addEventListener: function (t, f) { if (t === 'characteristicvaluechanged') this._ls.push(f); },
      removeEventListener: function (t, f) { this._ls = this._ls.filter(function (x) { return x !== f; }); },
      startNotifications: function () { return call('startNotifications', dev.id, svc.uuid, c.uuid).then(function () { return ch; }); },
      stopNotifications: function () { return Promise.resolve(ch); },
      writeValueWithoutResponse: function (d) { return call('write', dev.id, svc.uuid, c.uuid, toB64(d), false).then(function () {}); },
      writeValueWithResponse: function (d) { return call('write', dev.id, svc.uuid, c.uuid, toB64(d), true).then(function () {}); },
      writeValue: function (d) { return call('write', dev.id, svc.uuid, c.uuid, toB64(d), !c.props.writeWithoutResponse).then(function () {}); }
    };
    dev._chars[key] = ch;
    return ch;
  }
  function mkService(dev, s) {
    var svc = { uuid: s.uuid, device: dev, isPrimary: true };
    svc.getCharacteristics = function () { return Promise.resolve(s.chars.map(function (c) { return mkChar(dev, svc, c); })); };
    svc.getCharacteristic = function (u) {
      var uu = norm(u), c = s.chars.find(function (x) { return x.uuid === uu; });
      return c ? Promise.resolve(mkChar(dev, svc, c)) : Promise.reject(err('NotFoundError', 'Characteristic not found'));
    };
    return svc;
  }
  function mkDevice(info) {
    var old = devices[info.id];
    var dev = { id: info.id, name: info.name, _ls: old ? old._ls : [], _chars: {}, _svcs: [],
      addEventListener: function (t, f) { if (t === 'gattserverdisconnected' && this._ls.indexOf(f) < 0) this._ls.push(f); },
      removeEventListener: function (t, f) { this._ls = this._ls.filter(function (x) { return x !== f; }); } };
    dev.gatt = { device: dev, connected: false,
      connect: function () { return call('connect', dev.id).then(function (svcs) { dev._svcs = svcs; dev._chars = {}; dev.gatt.connected = true; return dev.gatt; }); },
      disconnect: function () { AndroidBle.disconnect(dev.id); },
      getPrimaryService: function (u) {
        var uu = norm(u), s = dev._svcs.find(function (x) { return x.uuid === uu; });
        return s ? Promise.resolve(mkService(dev, s)) : Promise.reject(err('NotFoundError', 'Service not found'));
      },
      getPrimaryServices: function () { return Promise.resolve(dev._svcs.map(function (s) { return mkService(dev, s); })); }
    };
    devices[dev.id] = dev;
    return dev;
  }
  var bt = {
    getAvailability: function () { return Promise.resolve(!!AndroidBle.available()); },
    requestDevice: function (opts) {
      var o = JSON.parse(JSON.stringify(opts || {}, function (k, v) {
        return (k === 'services' || k === 'optionalServices') && Array.isArray(v) ? v.map(norm) : v;
      }));
      return call('requestDevice', JSON.stringify(o)).then(mkDevice);
    }
  };
  try { Object.defineProperty(navigator, 'bluetooth', { value: bt, configurable: true }); } catch (e) {}
  try { Object.defineProperty(navigator, 'clipboard', { value: { writeText: function (t) { AndroidBle.copy(String(t)); return Promise.resolve(); } }, configurable: true }); } catch (e) {}
  if (!navigator.wakeLock) {
    try { Object.defineProperty(navigator, 'wakeLock', { value: { request: function () { return Promise.resolve({ release: function () { return Promise.resolve(); } }); } }, configurable: true }); } catch (e) {}
  }
})();
