/* Roche Web Client — Progressive Enhancement Script
   1. Persistent Podcast Player + seamless PJAX navigation while audio plays
   2. Live Article / Paper scroll progress (>= 90% + archive auto-logs consumed)
   3. Interactive k-way Ranking (j/k, J/K, 1-9, =, t, s, o, Enter + touch/mouse) */
(function () {
  "use strict";

  var STORAGE_KEY = "roche_active_audio_v1";
  var SPEEDS = [1, 1.25, 1.5, 1.75, 2];

  function getCsrfToken() {
    var body = document.body;
    return (body && body.getAttribute("data-csrf")) || "";
  }

  function postProgress(itemId, progress, keepalive) {
    if (!itemId || !isFinite(progress)) return;
    var clamped = Math.max(0, Math.min(1, progress));
    var csrf = getCsrfToken();
    var body = "_csrf=" + encodeURIComponent(csrf) + "&progress=" + clamped.toFixed(4);
    try {
      fetch("/web/items/" + encodeURIComponent(itemId) + "/progress", {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "X-Roche-Csrf": csrf
        },
        body: body,
        credentials: "same-origin",
        keepalive: Boolean(keepalive)
      }).catch(function () {});
    } catch (_) {}
  }

  /* --- 1. Persistent Podcast Player --- */
  function initAudioPlayer() {
    var bar = document.getElementById("roche-player-bar");
    var audio = document.getElementById("roche-audio");
    if (!bar || !audio || bar.dataset.bound === "1") return;
    bar.dataset.bound = "1";

    var titleEl = document.getElementById("roche-player-title");
    var subEl = document.getElementById("roche-player-sub");
    var speedBtn = document.getElementById("roche-player-speed");
    var closeBtn = document.getElementById("roche-player-close");
    var readerLink = document.getElementById("roche-player-link");

    var activeItem = null;
    var lastSentAt = 0;
    var completionSent = false;

    function saveSession() {
      if (!activeItem) {
        try { sessionStorage.removeItem(STORAGE_KEY); } catch (_) {}
        return;
      }
      var payload = {
        itemId: activeItem.itemId,
        title: activeItem.title,
        feed: activeItem.feed,
        src: activeItem.src,
        currentTime: audio.currentTime || 0,
        duration: audio.duration || activeItem.duration || 0,
        rate: audio.playbackRate || 1
      };
      try { sessionStorage.setItem(STORAGE_KEY, JSON.stringify(payload)); } catch (_) {}
    }

    function loadTrack(meta, autoplay) {
      activeItem = meta;
      completionSent = false;
      bar.hidden = false;
      if (titleEl) titleEl.textContent = meta.title || "Episode";
      if (subEl) subEl.textContent = meta.feed || "Podcast";
      if (readerLink) readerLink.href = "/reader/" + encodeURIComponent(meta.itemId);
      if (audio.getAttribute("src") !== meta.src) {
        audio.src = meta.src;
      }
      var rate = meta.rate || 1;
      audio.playbackRate = rate;
      if (speedBtn) speedBtn.textContent = rate + "x";

      var applyInitialSeek = function () {
        if (meta.currentTime && meta.currentTime > 1) {
          try { audio.currentTime = meta.currentTime; } catch (_) {}
        } else if (meta.progress && meta.progress > 0 && meta.progress < 0.98 && isFinite(audio.duration) && audio.duration > 0) {
          try { audio.currentTime = meta.progress * audio.duration; } catch (_) {}
        }
      };

      if (audio.readyState >= 1) {
        applyInitialSeek();
      } else {
        audio.addEventListener("loadedmetadata", applyInitialSeek, { once: true });
      }
      if (autoplay) {
        var p = audio.play();
        if (p && typeof p.catch === "function") p.catch(function () {});
      }
      saveSession();
    }

    audio.addEventListener("timeupdate", function () {
      if (!activeItem || !isFinite(audio.duration) || audio.duration <= 0) return;
      var frac = audio.currentTime / audio.duration;
      var now = Date.now();
      if (frac >= 0.98 && !completionSent) {
        completionSent = true;
        postProgress(activeItem.itemId, 1.0, true);
        saveSession();
      } else if (now - lastSentAt >= 5000) {
        lastSentAt = now;
        postProgress(activeItem.itemId, frac, false);
        saveSession();
      }
    });

    audio.addEventListener("pause", function () {
      if (!activeItem || !isFinite(audio.duration) || audio.duration <= 0) return;
      var frac = audio.currentTime / audio.duration;
      postProgress(activeItem.itemId, frac >= 0.98 ? 1.0 : frac, true);
      saveSession();
    });

    audio.addEventListener("ended", function () {
      if (!activeItem) return;
      completionSent = true;
      postProgress(activeItem.itemId, 1.0, true);
      saveSession();
    });

    if (speedBtn) {
      speedBtn.addEventListener("click", function () {
        var cur = audio.playbackRate || 1;
        var idx = SPEEDS.indexOf(cur);
        var next = SPEEDS[(idx + 1) % SPEEDS.length];
        audio.playbackRate = next;
        speedBtn.textContent = next + "x";
        saveSession();
      });
    }

    if (closeBtn) {
      closeBtn.addEventListener("click", function () {
        audio.pause();
        audio.removeAttribute("src");
        activeItem = null;
        bar.hidden = true;
        saveSession();
      });
    }

    document.addEventListener("click", function (ev) {
      var btn = ev.target && ev.target.closest ? ev.target.closest(".js-play-audio") : null;
      if (!btn) return;
      ev.preventDefault();
      loadTrack({
        itemId: btn.getAttribute("data-item-id") || "",
        title: btn.getAttribute("data-title") || "Audio",
        feed: btn.getAttribute("data-feed") || "",
        src: btn.getAttribute("data-src") || "",
        progress: parseFloat(btn.getAttribute("data-progress") || "0") || 0,
        duration: parseFloat(btn.getAttribute("data-duration") || "0") || 0,
        rate: audio.playbackRate || 1
      }, true);
    });

    function applyPjaxHtml(html, finalUrl, fallbackUrl, pushHistory, targetHash) {
      var doc = new DOMParser().parseFromString(html, "text/html");
      var nextMain = doc.getElementById("roche-main");
      var curMain = document.getElementById("roche-main");
      if (!nextMain || !curMain) {
        if (fallbackUrl) window.location.href = fallbackUrl;
        return false;
      }
      if (doc.body && doc.body.hasAttribute("data-csrf") && document.body) {
        document.body.setAttribute("data-csrf", doc.body.getAttribute("data-csrf") || "");
      }
      curMain.innerHTML = nextMain.innerHTML;
      var nextNav = doc.getElementById("roche-nav");
      var curNav = document.getElementById("roche-nav");
      if (nextNav && curNav) curNav.innerHTML = nextNav.innerHTML;
      document.title = doc.title;
      var dest = finalUrl || fallbackUrl;
      if (pushHistory && dest) {
        window.history.pushState({}, doc.title, dest + (targetHash || ""));
      }
      if (targetHash) {
        var anchorEl = document.getElementById(targetHash.slice(1));
        if (anchorEl && anchorEl.scrollIntoView) {
          anchorEl.scrollIntoView();
        } else {
          window.scrollTo(0, 0);
        }
      } else {
        window.scrollTo(0, 0);
      }
      initPageWidgets();
      return true;
    }

    function runPjaxFetch(reqUrl, fetchOpts, fallbackUrl, pushHistory, targetHash) {
      fetch(reqUrl, fetchOpts)
        .then(function (resp) {
          if (resp.redirected && resp.url && resp.url.indexOf("/login") !== -1) {
            window.location.href = resp.url;
            return null;
          }
          if (!resp.ok && resp.status !== 400 && resp.status !== 404 && resp.status !== 409) {
            if (fallbackUrl) window.location.href = fallbackUrl;
            return null;
          }
          return resp.text().then(function (html) {
            return { html: html, finalUrl: resp.url || reqUrl };
          });
        })
        .then(function (res) {
          if (!res) return;
          applyPjaxHtml(res.html, res.finalUrl, fallbackUrl, pushHistory, targetHash);
        })
        .catch(function () {
          if (fallbackUrl) window.location.href = fallbackUrl;
        });
    }

    /* Seamless PJAX navigation & form submission while audio is playing so playback never drops */
    document.addEventListener("click", function (ev) {
      if (!activeItem || audio.paused) return;
      if (ev.defaultPrevented || ev.button !== 0 || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) return;
      var link = ev.target && ev.target.closest ? ev.target.closest("a[href]") : null;
      if (!link || link.target || link.hasAttribute("download")) return;
      var url;
      try { url = new URL(link.href, window.location.origin); } catch (_) { return; }
      if (url.origin !== window.location.origin) return;
      if (url.pathname === window.location.pathname && url.search === window.location.search && url.hash) return;
      if (url.pathname.indexOf("/web/") === 0 || url.pathname.indexOf("/api/") === 0 || url.pathname === "/login") return;

      ev.preventDefault();
      runPjaxFetch(
        url.pathname + url.search,
        { credentials: "same-origin" },
        link.href,
        true,
        url.hash
      );
    });

    document.addEventListener("submit", function (ev) {
      if (!activeItem || audio.paused || ev.defaultPrevented) return;
      var form = ev.target;
      if (!form || form.tagName !== "FORM" || form.target) return;
      var actionRaw = form.getAttribute("action") || window.location.href;
      var actionUrl;
      try { actionUrl = new URL(actionRaw, window.location.origin); } catch (_) { return; }
      if (actionUrl.origin !== window.location.origin) return;
      if (actionUrl.pathname === "/login" || actionUrl.pathname === "/logout") return;

      var method = (form.getAttribute("method") || "GET").toUpperCase();
      var enctype = (form.getAttribute("enctype") || "application/x-www-form-urlencoded").toLowerCase();
      if (method !== "GET" && (method !== "POST" || enctype.indexOf("application/x-www-form-urlencoded") !== 0)) {
        return;
      }

      var fd = new FormData(form);
      if (ev.submitter && ev.submitter.name) {
        fd.append(ev.submitter.name, ev.submitter.value || "");
      }
      var params = new URLSearchParams();
      fd.forEach(function (val, key) {
        if (typeof val === "string") params.append(key, val);
      });

      ev.preventDefault();
      if (method === "GET") {
        var qs = params.toString();
        var getDest = actionUrl.pathname + (qs ? "?" + qs : "");
        runPjaxFetch(getDest, { credentials: "same-origin" }, getDest, true, "");
      } else {
        runPjaxFetch(
          actionUrl.pathname + actionUrl.search,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/x-www-form-urlencoded",
              "X-Roche-Csrf": getCsrfToken()
            },
            body: params.toString(),
            credentials: "same-origin",
            redirect: "follow"
          },
          window.location.href,
          true,
          ""
        );
      }
    });

    window.addEventListener("popstate", function () {
      if (!activeItem || audio.paused) return;
      runPjaxFetch(
        window.location.pathname + window.location.search,
        { credentials: "same-origin" },
        window.location.href,
        false,
        window.location.hash
      );
    });

    try {
      var saved = sessionStorage.getItem(STORAGE_KEY);
      if (saved) {
        var parsed = JSON.parse(saved);
        if (parsed && parsed.itemId && parsed.src) {
          loadTrack(parsed, false);
        }
      }
    } catch (_) {}
  }

  /* --- 2. Reader Scroll Progress --- */
  var readerCleanup = null;
  function initReaderProgress() {
    if (readerCleanup) {
      readerCleanup();
      readerCleanup = null;
    }
    var root = document.querySelector(".js-reader-root[data-item-id]");
    if (!root) return;
    var kind = root.getAttribute("data-kind") || "article";
    if (kind !== "article" && kind !== "paper") return;

    var itemId = root.getAttribute("data-item-id");
    var best = parseFloat(root.getAttribute("data-progress") || "0") || 0;
    var lastPosted = best;
    var timer = null;

    function measureFraction() {
      var docEl = document.documentElement;
      var maxScroll = Math.max(0, docEl.scrollHeight - window.innerHeight);
      if (maxScroll <= 8) return 1.0;
      return Math.max(0, Math.min(1, window.scrollY / maxScroll));
    }

    function checkProgress(forceFlush) {
      var frac = measureFraction();
      if (frac > best) best = frac;
      var crossedNinety = lastPosted < 0.9 && best >= 0.9;
      if (forceFlush || crossedNinety || best - lastPosted >= 0.05) {
        lastPosted = best;
        postProgress(itemId, best, Boolean(forceFlush));
      }
    }

    var onScroll = function () {
      if (timer) return;
      timer = window.setTimeout(function () {
        timer = null;
        checkProgress(false);
      }, 350);
    };

    window.addEventListener("scroll", onScroll, { passive: true });
    window.setTimeout(function () { checkProgress(false); }, 250);

    var archiveForm = root.querySelector(".js-reader-archive-form");
    if (archiveForm) {
      archiveForm.addEventListener("submit", function () {
        var hiddenProg = archiveForm.querySelector("input[name='progress']");
        var live = Math.max(best, measureFraction());
        if (hiddenProg) hiddenProg.value = live.toFixed(4);
      });
    }

    readerCleanup = function () {
      window.removeEventListener("scroll", onScroll);
      if (timer) window.clearTimeout(timer);
    };
  }

  /* --- 3. Interactive k-Way Ranking Board --- */
  var rankKeyCleanup = null;
  function initRankBoard() {
    if (rankKeyCleanup) {
      rankKeyCleanup();
      rankKeyCleanup = null;
    }
    var form = document.querySelector(".js-rank-form");
    if (!form) return;
    var list = form.querySelector(".js-rank-list");
    var hiddenInput = form.querySelector("input[name='ordering_json']");
    var tieAllBtn = form.querySelector(".js-rank-tie-all");
    var skipForm = document.querySelector(".js-rank-skip-form");
    if (!list || !hiddenInput) return;

    var selectedIdx = 0;

    function getCards() {
      return Array.prototype.slice.call(list.querySelectorAll(".js-rank-card"));
    }

    function syncBoard() {
      var cards = getCards();
      if (cards.length === 0) return;
      if (selectedIdx >= cards.length) selectedIdx = cards.length - 1;
      if (selectedIdx < 0) selectedIdx = 0;

      var groups = [];
      var displayRank = 1;
      cards.forEach(function (card, idx) {
        var id = card.getAttribute("data-entry-id");
        var tied = idx > 0 && card.getAttribute("data-tied") === "1";
        if (idx === 0) {
          card.setAttribute("data-tied", "0");
          groups.push([id]);
          displayRank = 1;
        } else if (tied) {
          groups[groups.length - 1].push(id);
        } else {
          displayRank = idx + 1;
          groups.push([id]);
        }
        card.classList.toggle("is-tied", tied);
        card.classList.toggle("is-selected", idx === selectedIdx);
        var posEl = card.querySelector(".js-rank-pos");
        if (posEl) posEl.textContent = (tied ? "= " : "") + "#" + displayRank;
        var tieBtn = card.querySelector(".js-card-tie");
        if (tieBtn) {
          tieBtn.disabled = idx === 0;
          tieBtn.textContent = tied ? "Untie" : "= Tie";
        }
      });
      hiddenInput.value = JSON.stringify(groups);
    }

    function moveCard(fromIdx, toIdx) {
      var cards = getCards();
      if (fromIdx < 0 || fromIdx >= cards.length || toIdx < 0 || toIdx >= cards.length || fromIdx === toIdx) return;
      var moving = cards[fromIdx];
      moving.setAttribute("data-tied", "0");
      if (toIdx < fromIdx) {
        list.insertBefore(moving, cards[toIdx]);
      } else {
        list.insertBefore(moving, cards[toIdx].nextSibling);
      }
      selectedIdx = toIdx;
      syncBoard();
    }

    function toggleTieAt(idx) {
      var cards = getCards();
      if (idx <= 0 || idx >= cards.length) return;
      var cur = cards[idx].getAttribute("data-tied") === "1";
      cards[idx].setAttribute("data-tied", cur ? "0" : "1");
      syncBoard();
    }

    function tieAll() {
      var cards = getCards();
      var allTied = cards.slice(1).every(function (c) { return c.getAttribute("data-tied") === "1"; });
      cards.forEach(function (c, idx) {
        c.setAttribute("data-tied", idx > 0 && !allTied ? "1" : "0");
      });
      syncBoard();
    }

    list.addEventListener("click", function (ev) {
      var card = ev.target && ev.target.closest ? ev.target.closest(".js-rank-card") : null;
      if (!card) return;
      var cards = getCards();
      var idx = cards.indexOf(card);
      if (idx === -1) return;
      selectedIdx = idx;

      if (ev.target.closest(".js-card-up")) {
        ev.preventDefault();
        moveCard(idx, idx - 1);
      } else if (ev.target.closest(".js-card-down")) {
        ev.preventDefault();
        moveCard(idx, idx + 1);
      } else if (ev.target.closest(".js-card-tie")) {
        ev.preventDefault();
        toggleTieAt(idx);
      } else {
        syncBoard();
      }
    });

    if (tieAllBtn) {
      tieAllBtn.addEventListener("click", function (ev) {
        ev.preventDefault();
        tieAll();
      });
    }

    var onKeyDown = function (ev) {
      var tag = ev.target && ev.target.tagName ? ev.target.tagName.toLowerCase() : "";
      if (tag === "input" || tag === "textarea" || tag === "select") return;
      if (ev.metaKey || ev.ctrlKey || ev.altKey) return;

      var cards = getCards();
      if (cards.length === 0) return;

      if (ev.key === "j" || (ev.key === "ArrowDown" && !ev.shiftKey)) {
        ev.preventDefault();
        selectedIdx = Math.min(cards.length - 1, selectedIdx + 1);
        syncBoard();
      } else if (ev.key === "k" || (ev.key === "ArrowUp" && !ev.shiftKey)) {
        ev.preventDefault();
        selectedIdx = Math.max(0, selectedIdx - 1);
        syncBoard();
      } else if (ev.key === "J" || (ev.key === "ArrowDown" && ev.shiftKey)) {
        ev.preventDefault();
        moveCard(selectedIdx, selectedIdx + 1);
      } else if (ev.key === "K" || (ev.key === "ArrowUp" && ev.shiftKey)) {
        ev.preventDefault();
        moveCard(selectedIdx, selectedIdx - 1);
      } else if (ev.key >= "1" && ev.key <= "9") {
        var target = parseInt(ev.key, 10) - 1;
        if (target < cards.length) {
          ev.preventDefault();
          moveCard(selectedIdx, target);
        }
      } else if (ev.key === "=") {
        ev.preventDefault();
        toggleTieAt(selectedIdx);
      } else if (ev.key === "t") {
        ev.preventDefault();
        tieAll();
      } else if (ev.key === "s" && skipForm) {
        ev.preventDefault();
        if (typeof skipForm.requestSubmit === "function") {
          skipForm.requestSubmit();
        } else {
          skipForm.submit();
        }
      } else if (ev.key === "o") {
        var url = cards[selectedIdx].getAttribute("data-open-url");
        if (url) {
          ev.preventDefault();
          window.open(url, "_blank", "noopener");
        }
      } else if (ev.key === "Enter") {
        ev.preventDefault();
        if (typeof form.requestSubmit === "function") {
          form.requestSubmit();
        } else {
          form.submit();
        }
      }
    };

    document.addEventListener("keydown", onKeyDown);
    rankKeyCleanup = function () { document.removeEventListener("keydown", onKeyDown); };
    syncBoard();
  }

  /* --- 4. OPML File Picker Helper --- */
  function initOpmlFileHelper() {
    var picker = document.querySelector(".js-opml-file");
    var area = document.querySelector(".js-opml-textarea");
    if (!picker || !area || picker.dataset.bound === "1") return;
    picker.dataset.bound = "1";
    picker.addEventListener("change", function () {
      var file = picker.files && picker.files[0];
      if (!file) return;
      var reader = new FileReader();
      reader.onload = function () {
        if (typeof reader.result === "string") area.value = reader.result;
      };
      reader.readAsText(file);
    });
  }

  function initPageWidgets() {
    initReaderProgress();
    initRankBoard();
    initOpmlFileHelper();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () {
      initAudioPlayer();
      initPageWidgets();
    });
  } else {
    initAudioPlayer();
    initPageWidgets();
  }
})();
