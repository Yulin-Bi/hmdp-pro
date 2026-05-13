(function () {
  if (window.__aiChatWidgetLoaded) {
    return;
  }
  window.__aiChatWidgetLoaded = true;

  function createEl(tag, className, html) {
    var el = document.createElement(tag);
    if (className) el.className = className;
    if (html !== undefined) el.innerHTML = html;
    return el;
  }

  function ensureStyles() {
    if (document.getElementById('ai-chat-widget-style')) return;
    var style = createEl('style');
    style.id = 'ai-chat-widget-style';
    style.innerHTML = [
      '.ai-chat-launcher{position:fixed;right:18px;bottom:96px;z-index:2147483647;width:58px;height:58px;border:none;border-radius:18px;background:linear-gradient(135deg,#1f7aff,#0bb8a7);color:#fff;box-shadow:0 14px 30px rgba(31,122,255,.28);cursor:pointer;display:flex;align-items:center;justify-content:center;gap:3px;flex-direction:column;font-size:12px;font-weight:700;}',
      '.ai-chat-launcher i{font-size:20px;line-height:1;}',
      '.ai-chat-panel{position:fixed;right:18px;bottom:168px;z-index:2147483647;width:calc(100vw - 36px);max-width:370px;height:540px;background:#fff;border-radius:16px;box-shadow:0 22px 56px rgba(15,23,42,.24);display:none;overflow:hidden;border:1px solid #dbeafe;font-family:Arial,"PingFang SC","Microsoft YaHei",sans-serif;}',
      '.ai-chat-panel.open{display:flex;flex-direction:column;}',
      '.ai-chat-header{display:flex;align-items:center;justify-content:space-between;padding:15px 16px;background:linear-gradient(135deg,#1f7aff,#155bd4);color:#fff;}',
      '.ai-chat-title{font-size:16px;font-weight:800;}',
      '.ai-chat-subtitle{font-size:12px;opacity:.85;margin-top:3px;}',
      '.ai-chat-close{width:30px;height:30px;border:none;border-radius:10px;background:rgba(255,255,255,.16);color:#fff;font-size:20px;line-height:1;cursor:pointer;}',
      '.ai-chat-body{flex:1;padding:14px;background:linear-gradient(180deg,#f5f9ff,#fff);overflow:auto;}',
      '.ai-chat-item{margin-bottom:12px;display:flex;}',
      '.ai-chat-item.user{justify-content:flex-end;}',
      '.ai-chat-bubble{max-width:84%;padding:10px 12px;border-radius:14px;font-size:13px;line-height:1.55;white-space:pre-wrap;word-break:break-word;}',
      '.ai-chat-item.user .ai-chat-bubble{background:linear-gradient(135deg,#1f7aff,#0bb8a7);color:#fff;border-bottom-right-radius:4px;}',
      '.ai-chat-item.assistant .ai-chat-bubble{background:#fff;color:#172033;border:1px solid #dbeafe;border-bottom-left-radius:4px;box-shadow:0 6px 18px rgba(31,122,255,.06);}',
      '.ai-chat-empty{color:#64748b;font-size:12px;text-align:center;padding:28px 12px;}',
      '.ai-chat-quick{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px;}',
      '.ai-chat-chip{border:1px solid #bfdbfe;background:#fff;color:#155bd4;border-radius:999px;padding:6px 10px;font-size:12px;cursor:pointer;}',
      '.ai-chat-footer{padding:12px;border-top:1px solid #dbeafe;background:#fff;}',
      '.ai-chat-input-row{display:flex;gap:8px;align-items:flex-end;}',
      '.ai-chat-input{flex:1;min-height:42px;max-height:110px;padding:10px 12px;border-radius:12px;border:1px solid #bfdbfe;resize:none;outline:none;font-size:13px;line-height:1.5;background:#f8fbff;}',
      '.ai-chat-input:focus{border-color:#1f7aff;background:#fff;}',
      '.ai-chat-send{width:68px;height:42px;border:none;border-radius:12px;background:#1f7aff;color:#fff;cursor:pointer;font-size:14px;font-weight:700;}',
      '.ai-chat-send:disabled{opacity:.55;cursor:not-allowed;}',
      '.ai-chat-meta{font-size:12px;color:#64748b;margin-top:6px;}'
    ].join('\n');
    document.head.appendChild(style);
  }

  function getConversationId() {
    return sessionStorage.getItem('aiChatConversationId') || '';
  }

  function setConversationId(conversationId) {
    if (conversationId) sessionStorage.setItem('aiChatConversationId', conversationId);
  }

  function getUrlParam(name) {
    var reg = new RegExp('(^|&)' + name + '=([^&]*)(&|$)', 'i');
    var result = window.location.search.substr(1).match(reg);
    return result == null ? '' : decodeURI(result[2]);
  }

  function getShopIdFromPage() {
    return window.location.pathname.indexOf('shop-detail.html') >= 0 ? Number(getUrlParam('id') || 0) || null : null;
  }

  function escapeText(text) {
    return String(text == null ? '' : text)
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

  function normalizeChatResponse(response) {
    var data = response && response.data !== undefined ? response.data : (response || {});
    for (var i = 0; i < 3; i++) {
      if (data && data.success !== undefined && data.data !== undefined) {
        data = data.data || {};
        continue;
      }
      if (data && data.data && data.answer === undefined && data.message === undefined) {
        data = data.data;
        continue;
      }
      break;
    }
    if (typeof data === 'string') {
      try {
        data = JSON.parse(data);
      } catch (e) {
        data = { answer: data };
      }
    }
    return data || {};
  }

  function getAnswerText(data) {
    if (data.answer) return data.answer;
    if (data.message) return data.message;
    return '暂时没有返回内容，请换个问法再试。';
  }

  function initWidget() {
    if (!window.axios) {
      window.setTimeout(initWidget, 50);
      return;
    }

    ensureStyles();

    var oldLaunchers = document.querySelectorAll('#ai-chat-launcher');
    for (var i = 1; i < oldLaunchers.length; i++) oldLaunchers[i].parentNode.removeChild(oldLaunchers[i]);

    var launcher = document.getElementById('ai-chat-launcher') || createEl('button', 'ai-chat-launcher');
    launcher.id = 'ai-chat-launcher';
    launcher.className = 'ai-chat-launcher';
    launcher.type = 'button';
    launcher.setAttribute('aria-label', '智能客服');
    launcher.removeAttribute('style');
    launcher.innerHTML = '<i class="el-icon-service"></i><span>客服</span>';

    var panel = document.getElementById('ai-chat-panel') || createEl('div', 'ai-chat-panel');
    panel.id = 'ai-chat-panel';
    panel.className = 'ai-chat-panel';
    panel.innerHTML =
      '<div class="ai-chat-header">' +
        '<div><div class="ai-chat-title">智能客服</div><div class="ai-chat-subtitle">查店铺、推荐门店、预约到店</div></div>' +
        '<button class="ai-chat-close" type="button">×</button>' +
      '</div>' +
      '<div class="ai-chat-body">' +
        '<div class="ai-chat-quick">' +
          '<button class="ai-chat-chip" type="button">这家店几点关门？</button>' +
          '<button class="ai-chat-chip" type="button">附近有什么推荐？</button>' +
          '<button class="ai-chat-chip" type="button">帮我预约明晚7点</button>' +
        '</div>' +
        '<div class="ai-chat-empty">你好，我可以帮你查店铺信息、推荐门店，也能协助预约到店。</div>' +
      '</div>' +
      '<div class="ai-chat-footer">' +
        '<div class="ai-chat-input-row">' +
          '<textarea class="ai-chat-input" placeholder="输入你的问题，例如：这家店人均多少？"></textarea>' +
          '<button class="ai-chat-send" type="button">发送</button>' +
        '</div>' +
        '<div class="ai-chat-meta">登录后可使用，会自动带上当前店铺上下文。</div>' +
      '</div>';

    if (!launcher.parentNode) document.body.appendChild(launcher);
    if (!panel.parentNode) document.body.appendChild(panel);

    var bodyEl = panel.querySelector('.ai-chat-body');
    var inputEl = panel.querySelector('.ai-chat-input');
    var sendEl = panel.querySelector('.ai-chat-send');
    var closeEl = panel.querySelector('.ai-chat-close');
    var emptyEl = panel.querySelector('.ai-chat-empty');

    function openPanel() {
      panel.classList.add('open');
      window.setTimeout(function () { inputEl.focus(); }, 0);
    }

    function closePanel() {
      panel.classList.remove('open');
    }

    function sendMessage(text) {
      text = (text || inputEl.value).trim();
      if (!text || sendEl.disabled) return;
      emptyEl.style.display = 'none';
      renderMessage(bodyEl, 'user', text);
      inputEl.value = '';
      sendEl.disabled = true;
      axios.post('/ai/chat', {
        message: text,
        conversationId: getConversationId(),
        shopId: getShopIdFromPage()
      }, { timeout: 180000 }).then(function (response) {
        var data = normalizeChatResponse(response);
        if (data.conversationId) setConversationId(data.conversationId);
        renderMessage(bodyEl, 'assistant', getAnswerText(data));
      }).catch(function (error) {
        var message = '服务暂时不可用，请稍后再试。';
        if (typeof error === 'string') message = error;
        else if (error && error.code === 'ECONNABORTED') message = '请求超时，请稍后重试。';
        else if (error && error.response && error.response.data && error.response.data.errorMsg) message = error.response.data.errorMsg;
        else if (error && error.message) message = error.message;
        renderMessage(bodyEl, 'assistant', message);
      }).finally(function () {
        sendEl.disabled = false;
      });
    }

    launcher.onclick = openPanel;
    closeEl.onclick = closePanel;
    sendEl.onclick = function () { sendMessage(); };
    inputEl.onkeydown = function (event) {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage();
      }
    };
    Array.prototype.forEach.call(panel.querySelectorAll('.ai-chat-chip'), function (chip) {
      chip.onclick = function () { sendMessage(chip.textContent); };
    });
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
