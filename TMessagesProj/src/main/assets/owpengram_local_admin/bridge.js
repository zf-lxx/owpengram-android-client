/* owpengram local admin bridge: no network, all /admin/api goes through the session channel. */
(function () {
  var pending = {};

  window.__owpengramLocal = {
    resolve: function (id, status, body) {
      var p = pending[id];
      if (!p) return;
      delete pending[id];
      p.resolve({ status: status, body: body });
    },
    reject: function (id, message) {
      var p = pending[id];
      if (!p) return;
      delete pending[id];
      p.reject(new Error(message || "request failed"));
    }
  };

  function nativeRequest(method, path, body) {
    return new Promise(function (resolve, reject) {
      if (!window.OwpengramNative) {
        reject(new Error("本地通道不可用"));
        return;
      }
      var id = "r" + Date.now() + "_" + Math.random().toString(36).slice(2, 10);
      pending[id] = { resolve: resolve, reject: reject };
      window.OwpengramNative.request(id, method || "GET", path, body || "");
      setTimeout(function () {
        if (pending[id]) {
          delete pending[id];
          reject(new Error("管理页请求超时"));
        }
      }, 60000);
    });
  }

  function pathFromInput(input) {
    var url = typeof input === "string" ? input : (input && input.url) || "";
    try {
      return new URL(url, location.href).pathname;
    } catch (e) {
      return url;
    }
  }

  window.fetch = function (input, init) {
    var path = pathFromInput(input);
    if (path.indexOf("/admin/api/") !== -1) {
      var method = (init && init.method) || "GET";
      var body = init && init.body != null ? String(init.body) : "";
      return nativeRequest(method, path, body).then(function (res) {
        var data = res.body == null ? "" : String(res.body);
        return new Response(data, {
          status: res.status || 200,
          headers: { "Content-Type": "application/json" }
        });
      });
    }
    return Promise.reject(new Error("此页面不能访问外部网络"));
  };

  var scheme = "light";
  try {
    if (window.OwpengramNative && window.OwpengramNative.theme) {
      scheme = window.OwpengramNative.theme() || "light";
    }
  } catch (e) {}

  window.Telegram = window.Telegram || {};
  window.Telegram.WebApp = {
    initData: "owpengram-local",
    initDataUnsafe: {},
    colorScheme: scheme,
    ready: function () {},
    expand: function () {},
    close: function () {
      try { window.OwpengramNative.close(); } catch (e) {}
    },
    onEvent: function () {},
    HapticFeedback: {
      impactOccurred: function (t) {
        try { window.OwpengramNative.haptic(t || "light"); } catch (e) {}
      }
    }
  };
})();
