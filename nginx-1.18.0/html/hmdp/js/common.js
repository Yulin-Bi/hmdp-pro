// let commonURL = "http://192.168.50.115:8081";
let commonURL = "/api";
// 设置后台服务地址
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 10000;
// request拦截器，将用户token放入头中
axios.interceptors.request.use(
  config => {
    const token = sessionStorage.getItem("token");
    if (token) config.headers['authorization'] = token
    return config
  },
  error => {
    console.log(error)
    return Promise.reject(error)
  }
)
axios.interceptors.response.use(function (response) {
  // 判断执行结果
  if (!response.data.success) {
    return Promise.reject(response.data.errorMsg)
  }
  return response.data;
}, function (error) {
  // 一般是服务端异常或者网络异常
  console.log(error)
  if (error && error.response && error.response.status == 401) {
    // 未登录，跳转
    setTimeout(() => {
      location.href = "/login.html"
    }, 200);
    return Promise.reject("请先登录");
  }
  if (error && error.code === 'ECONNABORTED') {
    return Promise.reject("请求超时，请稍后重试");
  }
  return Promise.reject("服务器异常");
});
axios.defaults.paramsSerializer = function(params) {
  let p = "";
  Object.keys(params).forEach(k => {
    if(params[k]){
      p = p + "&" + k + "=" + params[k]
    }
  })
  return p;
}
const util = {
  commonURL,
  getUrlParam(name) {
    let reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
    let r = window.location.search.substr(1).match(reg);
    if (r != null) {
      return decodeURI(r[2]);
    }
    return "";
  },
  formatPrice(val) {
    if (typeof val === 'string') {
      if (isNaN(val)) {
        return null;
      }
      // 价格转为整数
      const index = val.lastIndexOf(".");
      let p = "";
      if (index < 0) {
        // 无小数
        p = val + "00";
      } else if (index === p.length - 2) {
        // 1位小数
        p = val.replace("\.", "") + "0";
      } else {
        // 2位小数
        p = val.replace("\.", "")
      }
      return parseInt(p);
    } else if (typeof val === 'number') {
      if (!val) {
        return null;
      }
      const s = val + '';
      if (s.length === 0) {
        return "0.00";
      }
      if (s.length === 1) {
        return "0.0" + val;
      }
      if (s.length === 2) {
        return "0." + val;
      }
      const i = s.indexOf(".");
      if (i < 0) {
        return s.substring(0, s.length - 2) + "." + s.substring(s.length - 2)
      }
      const num = s.substring(0, i) + s.substring(i + 1);
      if (i === 1) {
        // 1位整数
        return "0.0" + num;
      }
      if (i === 2) {
        return "0." + num;
      }
      if (i > 2) {
        return num.substring(0, i - 2) + "." + num.substring(i - 2)
      }
    }
  }
}

;(function () {
  return;
  if (window.__aiChatWidgetLoaded) {
    return;
  }
  window.__aiChatWidgetLoaded = true;

  function createEl(tag, className, html) {
    var el = document.createElement(tag);
    if (className) {
      el.className = className;
    }
    if (html !== undefined) {
      el.innerHTML = html;
    }
    return el;
  }

  function ensureStyles() {
    if (document.getElementById('ai-chat-widget-style')) {
      return;
    }
    var style = createEl('style');
    style.id = 'ai-chat-widget-style';
    style.innerHTML = '\n      .ai-chat-launcher{position:fixed;right:18px;bottom:96px;z-index:2147483647;width:60px;height:60px;border:none;border-radius:50%;background:linear-gradient(135deg,#1f7aff,#0bb8a7);color:#fff;box-shadow:0 14px 32px rgba(0,0,0,.26);cursor:pointer;font-size:22px;display:flex;align-items:center;justify-content:center;}\n      .ai-chat-panel{position:fixed;right:18px;bottom:172px;z-index:2147483647;width:calc(100vw - 36px);max-width:360px;height:520px;background:#fff;border-radius:18px;box-shadow:0 18px 48px rgba(15,23,42,.26);display:none;overflow:hidden;border:1px solid rgba(148,163,184,.22);font-family:Arial,"PingFang SC","Microsoft YaHei",sans-serif;}\n      .ai-chat-panel.open{display:flex;flex-direction:column;}\n      .ai-chat-header{display:flex;align-items:center;justify-content:space-between;padding:14px 16px;background:linear-gradient(135deg,#0f172a,#1e293b);color:#fff;}\n      .ai-chat-title{font-size:15px;font-weight:700;}\n      .ai-chat-subtitle{font-size:12px;opacity:.75;margin-top:2px;}\n      .ai-chat-body{flex:1;padding:14px;background:#f8fafc;overflow:auto;}\n      .ai-chat-item{margin-bottom:12px;display:flex;}\n      .ai-chat-item.user{justify-content:flex-end;}\n      .ai-chat-bubble{max-width:84%;padding:10px 12px;border-radius:14px;font-size:13px;line-height:1.55;white-space:pre-wrap;word-break:break-word;}\n      .ai-chat-item.user .ai-chat-bubble{background:#1f7aff;color:#fff;border-bottom-right-radius:4px;}\n      .ai-chat-item.assistant .ai-chat-bubble{background:#fff;color:#0f172a;border:1px solid #e2e8f0;border-bottom-left-radius:4px;}\n      .ai-chat-footer{padding:12px;border-top:1px solid #e2e8f0;background:#fff;}\n      .ai-chat-input-row{display:flex;gap:8px;align-items:flex-end;}\n      .ai-chat-input{flex:1;min-height:42px;max-height:110px;padding:10px 12px;border-radius:12px;border:1px solid #cbd5e1;resize:none;outline:none;font-size:13px;line-height:1.5;}\n      .ai-chat-send{width:72px;height:42px;border:none;border-radius:12px;background:#0f172a;color:#fff;cursor:pointer;font-size:14px;}\n      .ai-chat-send:disabled{opacity:.55;cursor:not-allowed;}\n      .ai-chat-close{background:transparent;border:none;color:#fff;font-size:22px;line-height:1;cursor:pointer;}\n      .ai-chat-empty{color:#64748b;font-size:12px;text-align:center;padding:24px 10px;}\n      .ai-chat-meta{font-size:12px;color:#64748b;margin-top:4px;}\n    ';
    document.head.appendChild(style);
  }

  function getConversationId() {
    return sessionStorage.getItem('aiChatConversationId') || '';
  }

  function setConversationId(conversationId) {
    if (conversationId) {
      sessionStorage.setItem('aiChatConversationId', conversationId);
    }
  }

  function getUrlParam(name) {
    var reg = new RegExp('(^|&)' + name + '=([^&]*)(&|$)', 'i');
    var result = window.location.search.substr(1).match(reg);
    if (result != null) {
      return decodeURI(result[2]);
    }
    return '';
  }

  function getShopIdFromPage() {
    return window.location.pathname.indexOf('shop-detail.html') >= 0 ? Number(getUrlParam('id') || 0) || null : null;
  }

  function escapeText(text) {
    return String(text)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function renderMessage(listEl, role, text) {
    var item = createEl('div', 'ai-chat-item ' + role);
    var bubble = createEl('div', 'ai-chat-bubble', escapeText(text));
    item.appendChild(bubble);
    listEl.appendChild(item);
    listEl.scrollTop = listEl.scrollHeight;
  }

  function initWidget() {
    if (document.getElementById('ai-chat-launcher') && document.getElementById('ai-chat-panel')) {
      return;
    }

    ensureStyles();

    var launcher = document.getElementById('ai-chat-launcher') || createEl('button', 'ai-chat-launcher', '客服');
    launcher.id = 'ai-chat-launcher';
    launcher.type = 'button';
    launcher.setAttribute('aria-label', '智能客服');

    var panel = document.getElementById('ai-chat-panel') || createEl('div', 'ai-chat-panel');
    panel.id = 'ai-chat-panel';
    if (!panel.innerHTML) {
      panel.innerHTML = '\n        <div class="ai-chat-header">\n          <div>\n            <div class="ai-chat-title">智能客服</div>\n            <div class="ai-chat-subtitle">可查询店铺信息，也可预约到店</div>\n          </div>\n          <button class="ai-chat-close" type="button">×</button>\n        </div>\n        <div class="ai-chat-body"><div class="ai-chat-empty">发送问题后，客服会给出店铺信息或预约帮助</div></div>\n        <div class="ai-chat-footer">\n          <div class="ai-chat-input-row">\n            <textarea class="ai-chat-input" placeholder="例如：这家店几点关门？或者帮我预约明天晚上 7 点到店"></textarea>\n            <button class="ai-chat-send" type="button">发送</button>\n          </div>\n          <div class="ai-chat-meta">登录后可直接使用，预约时会自动记住当前会话。</div>\n        </div>\n      ';
    }

    document.body.appendChild(launcher);
    document.body.appendChild(panel);

    var bodyEl = panel.querySelector('.ai-chat-body');
    var inputEl = panel.querySelector('.ai-chat-input');
    var sendEl = panel.querySelector('.ai-chat-send');
    var closeEl = panel.querySelector('.ai-chat-close');
    var emptyEl = panel.querySelector('.ai-chat-empty');

    function openPanel() {
      panel.classList.add('open');
      emptyEl.style.display = bodyEl.children.length > 0 ? 'none' : 'block';
      window.setTimeout(function () {
        inputEl.focus();
      }, 0);
    }

    function closePanel() {
      panel.classList.remove('open');
    }

    function sendMessage() {
      var text = inputEl.value.trim();
      if (!text) {
        return;
      }
      emptyEl.style.display = 'none';
      renderMessage(bodyEl, 'user', text);
      inputEl.value = '';
      sendEl.disabled = true;
      var payload = {
        message: text,
        conversationId: getConversationId(),
        shopId: getShopIdFromPage()
      };
      axios.post('/ai/chat', payload).then(function (response) {
        var data = response.data || response;
        if (data.conversationId) {
          setConversationId(data.conversationId);
        }
        renderMessage(bodyEl, 'assistant', data.answer || '暂时没有返回内容');
      }).catch(function (error) {
        var message = error && error.response && error.response.data && error.response.data.errorMsg ? error.response.data.errorMsg : (error || '服务器异常');
        renderMessage(bodyEl, 'assistant', message);
      }).finally(function () {
        sendEl.disabled = false;
      });
    }

    launcher.addEventListener('click', openPanel);
    closeEl.addEventListener('click', closePanel);
    sendEl.addEventListener('click', sendMessage);
    inputEl.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage();
      }
    });

    if (getConversationId()) {
      emptyEl.textContent = '已恢复上次会话，可以继续追问';
    }
  }

  function bootstrapWidget() {
    if (document.body) {
      initWidget();
      return;
    }
    window.setTimeout(bootstrapWidget, 0);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bootstrapWidget);
    window.addEventListener('load', bootstrapWidget, { once: true });
  } else {
    bootstrapWidget();
  }
})();
