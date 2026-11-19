/* Roche Web Client — Progressive Enhancement Script
   1. Persistent Podcast Player + seamless PJAX navigation while audio plays
   2. Live Article / Paper scroll progress (>= 90% + archive auto-logs consumed)
   3. Interactive k-way Ranking (explicit tie-bucket model: j/k, J/K, 1-9, =, t, s, o, Enter + touch/mouse) */
(() => {
  "use strict";

  const STORAGE_KEY = "roche_active_audio_v1";
  const SPEEDS = [1, 1.25, 1.5, 1.75, 2];

  const getCsrfToken = () => document.body?.getAttribute("data-csrf") ?? "";

  const postProgress = (itemId, progress, keepalive = false) => {
    if (!itemId || !Number.isFinite(progress)) return;
    const clamped = Math.max(0, Math.min(1, progress));
    const csrf = getCsrfToken();
    const body = `_csrf=${encodeURIComponent(csrf)}&progress=${clamped.toFixed(4)}`;
    try {
      fetch(`/web/items/${encodeURIComponent(itemId)}/progress`, {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "X-Roche-Csrf": csrf,
        },
        body,
        credentials: "same-origin",
        keepalive: Boolean(keepalive),
      }).catch(() => {});
    } catch {
      // Ignore transient network failures during background progress beacons.
    }
  };

  const hideRefreshBanner = () => {
    const banner = document.getElementById("roche-refresh-banner");
    if (banner) {
      banner.hidden = true;
      banner.setAttribute("data-active", "0");
    }
  };

  const showInlinePjaxError = (message) => {
    hideRefreshBanner();
    const curMain = document.getElementById("roche-main");
    if (!curMain) return;
    curMain.querySelector(".js-pjax-error")?.remove();
    const banner = document.createElement("div");
    banner.className = "notice notice-error js-pjax-error";
    banner.setAttribute("role", "alert");
    banner.textContent =
      message || "Request failed. Audio playback is unaffected — please try again.";
    curMain.prepend(banner);
  };

  /* Confirmation handler for destructive forms (runs before PJAX & refresh-banner listeners) */
  document.addEventListener("submit", (ev) => {
    const form = ev.target;
    const msg = form?.getAttribute?.("data-confirm");
    if (msg && !window.confirm(msg)) {
      ev.preventDefault();
      ev.stopImmediatePropagation();
    }
  });

  /* --- 1. Persistent Podcast Player --- */
  const initAudioPlayer = () => {
    const bar = document.getElementById("roche-player-bar");
    const audio = document.getElementById("roche-audio");
    if (!bar || !audio || bar.dataset.bound === "1") return;
    bar.dataset.bound = "1";

    const titleEl = document.getElementById("roche-player-title");
    const subEl = document.getElementById("roche-player-sub");
    const speedBtn = document.getElementById("roche-player-speed");
    const closeBtn = document.getElementById("roche-player-close");
    const readerLink = document.getElementById("roche-player-link");

    let activeItem = null;
    let desiredRate = 1;
    let lastSentAt = 0;
    let completionSent = false;
    let pjaxNavigated = false;
    let activePjaxController = null;
    let pendingSeekListener = null;
    let lastRenderedUrl = `${window.location.pathname}${window.location.search}`;

    const clearPendingSeekListener = () => {
      if (pendingSeekListener) {
        audio.removeEventListener("loadedmetadata", pendingSeekListener);
        pendingSeekListener = null;
      }
    };

    const applyPlaybackRate = (rate) => {
      desiredRate = rate;
      audio.defaultPlaybackRate = rate;
      audio.playbackRate = rate;
    };

    const flushActiveProgress = (keepalive) => {
      if (!activeItem?.itemId || !Number.isFinite(audio.duration) || audio.duration <= 0) return;
      const frac = audio.currentTime / audio.duration;
      postProgress(activeItem.itemId, frac >= 0.98 ? 1.0 : frac, keepalive);
    };

    const saveSession = () => {
      if (!activeItem) {
        try {
          sessionStorage.removeItem(STORAGE_KEY);
        } catch {}
        return;
      }
      const payload = {
        itemId: activeItem.itemId,
        title: activeItem.title,
        feed: activeItem.feed,
        src: activeItem.src,
        currentTime: audio.currentTime || 0,
        duration: audio.duration || activeItem.duration || 0,
        rate: desiredRate || audio.playbackRate || 1,
      };
      try {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
      } catch {}
    };

    const loadTrack = (meta, autoplay) => {
      clearPendingSeekListener();
      if (activeItem?.itemId && activeItem.itemId !== meta.itemId) {
        flushActiveProgress(true);
      }
      activeItem = meta;
      completionSent = false;
      bar.hidden = false;
      document.body?.classList.add("has-player-bar");
      if (titleEl) titleEl.textContent = meta.title || "Episode";
      if (subEl) subEl.textContent = meta.feed || "Podcast";
      if (readerLink) readerLink.href = `/reader/${encodeURIComponent(meta.itemId)}`;

      const nextRate = meta.rate || desiredRate || 1;
      if (audio.getAttribute("src") !== meta.src) {
        audio.src = meta.src;
      }
      applyPlaybackRate(nextRate);
      if (speedBtn) speedBtn.textContent = `${desiredRate}x`;

      const applyInitialSeek = () => {
        pendingSeekListener = null;
        if (activeItem !== meta) return;
        applyPlaybackRate(desiredRate);
        if (meta.currentTime && meta.currentTime > 1) {
          try {
            audio.currentTime = meta.currentTime;
          } catch {}
        } else if (
          meta.progress &&
          meta.progress > 0 &&
          meta.progress < 0.98 &&
          Number.isFinite(audio.duration) &&
          audio.duration > 0
        ) {
          try {
            audio.currentTime = meta.progress * audio.duration;
          } catch {}
        }
      };

      if (audio.readyState >= 1) {
        applyInitialSeek();
      } else {
        pendingSeekListener = applyInitialSeek;
        audio.addEventListener("loadedmetadata", applyInitialSeek, { once: true });
      }
      if (autoplay) {
        audio.play?.()?.catch?.(() => {});
      }
      saveSession();
    };

    audio.addEventListener("timeupdate", () => {
      if (!activeItem || !Number.isFinite(audio.duration) || audio.duration <= 0) return;
      const frac = audio.currentTime / audio.duration;
      const now = Date.now();
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

    audio.addEventListener("pause", () => {
      flushActiveProgress(true);
      saveSession();
    });

    audio.addEventListener("ended", () => {
      if (!activeItem) return;
      completionSent = true;
      postProgress(activeItem.itemId, 1.0, true);
      saveSession();
    });

    window.addEventListener("pagehide", () => {
      flushActiveProgress(true);
      saveSession();
    });

    speedBtn?.addEventListener("click", () => {
      const cur = desiredRate || audio.playbackRate || 1;
      const idx = SPEEDS.indexOf(cur);
      const next = SPEEDS[(idx + 1) % SPEEDS.length];
      applyPlaybackRate(next);
      speedBtn.textContent = `${next}x`;
      saveSession();
    });

    closeBtn?.addEventListener("click", () => {
      clearPendingSeekListener();
      flushActiveProgress(true);
      audio.pause();
      audio.removeAttribute("src");
      audio.load();
      activeItem = null;
      bar.hidden = true;
      document.body?.classList.remove("has-player-bar");
      saveSession();
    });

    document.addEventListener("click", (ev) => {
      const btn = ev.target?.closest?.(".js-play-audio");
      if (!btn) return;
      ev.preventDefault();
      const clickedId = btn.getAttribute("data-item-id") || "";
      if (activeItem && clickedId && activeItem.itemId === clickedId && audio.getAttribute("src")) {
        if (audio.paused) {
          audio.play?.()?.catch?.(() => {});
        } else {
          audio.pause();
        }
        return;
      }
      loadTrack(
        {
          itemId: clickedId,
          title: btn.getAttribute("data-title") || "Audio",
          feed: btn.getAttribute("data-feed") || "",
          src: btn.getAttribute("data-src") || "",
          progress: Number.parseFloat(btn.getAttribute("data-progress") || "0") || 0,
          duration: Number.parseFloat(btn.getAttribute("data-duration") || "0") || 0,
          rate: desiredRate || audio.playbackRate || 1,
        },
        true
      );
    });

    const isAudioPlaying = () => Boolean(activeItem && !audio.paused);

    const applyPjaxHtml = (html, finalUrl, fallbackUrl, pushHistory, targetHash) => {
      const doc = new DOMParser().parseFromString(html, "text/html");
      const nextMain = doc.getElementById("roche-main");
      const curMain = document.getElementById("roche-main");
      if (!nextMain || !curMain) {
        if (!isAudioPlaying() && fallbackUrl) {
          window.location.href = fallbackUrl;
        } else {
          showInlinePjaxError("Received an unexpected page response.");
        }
        return false;
      }
      if (doc.body?.hasAttribute("data-csrf") && document.body) {
        document.body.setAttribute("data-csrf", doc.body.getAttribute("data-csrf") || "");
      }
      curMain.replaceChildren(...Array.from(nextMain.childNodes));
      const nextNav = doc.getElementById("roche-nav");
      const curNav = document.getElementById("roche-nav");
      if (nextNav && curNav) {
        curNav.replaceChildren(...Array.from(nextNav.childNodes));
      }
      const nextRefresh = doc.getElementById("roche-refresh-banner");
      const curRefresh = document.getElementById("roche-refresh-banner");
      if (nextRefresh && curRefresh) {
        curRefresh.hidden = nextRefresh.hidden;
        curRefresh.setAttribute("data-active", nextRefresh.getAttribute("data-active") || "0");
      }
      document.title = doc.title;
      const dest = finalUrl || fallbackUrl;
      if (dest) {
        try {
          const parsedDest = new URL(dest, window.location.origin);
          lastRenderedUrl = `${parsedDest.pathname}${parsedDest.search}`;
        } catch {}
      }
      if (pushHistory && dest) {
        window.history.pushState({}, doc.title, `${dest}${targetHash || ""}`);
        pjaxNavigated = true;
      }
      if (targetHash) {
        const anchorEl = document.getElementById(targetHash.slice(1));
        if (anchorEl?.scrollIntoView) {
          anchorEl.scrollIntoView();
        } else {
          window.scrollTo(0, 0);
        }
      } else {
        window.scrollTo(0, 0);
        curMain.focus?.({ preventScroll: true });
      }
      initPageWidgets();
      return true;
    };

    const runPjaxFetch = async (
      reqUrl,
      fetchOpts,
      fallbackUrl,
      pushHistory,
      targetHash,
      isPost
    ) => {
      if (activePjaxController) {
        try {
          activePjaxController.abort();
        } catch {}
      }
      const controller = typeof AbortController === "function" ? new AbortController() : null;
      activePjaxController = controller;
      const opts = { ...(fetchOpts || {}) };
      if (controller) opts.signal = controller.signal;

      try {
        const resp = await fetch(reqUrl, opts);
        if (controller && activePjaxController !== controller) return;
        if (resp.redirected && resp.url?.includes("/login")) {
          window.location.href = resp.url;
          return;
        }
        if (!resp.ok && resp.status !== 400 && resp.status !== 404 && resp.status !== 409) {
          if (!isPost && !isAudioPlaying() && fallbackUrl) {
            window.location.href = fallbackUrl;
          } else {
            showInlinePjaxError(`Server error (${resp.status}). Please try again.`);
          }
          return;
        }
        // Never push a non-redirected POST endpoint (/web/...) into browser history.
        const shouldPush = Boolean(pushHistory && (!isPost || resp.redirected));
        const html = await resp.text();
        if (controller && activePjaxController !== controller) return;
        applyPjaxHtml(html, resp.url || reqUrl, fallbackUrl, shouldPush, targetHash);
      } catch (err) {
        if (err?.name === "AbortError") return;
        if (controller && activePjaxController !== controller) return;
        if (!isPost && !isAudioPlaying() && fallbackUrl) {
          window.location.href = fallbackUrl;
        } else {
          showInlinePjaxError("Network error while loading page. Audio playback continues.");
        }
      } finally {
        if (controller && activePjaxController === controller) {
          activePjaxController = null;
        }
      }
    };

    /* Seamless PJAX navigation & form submission while audio is playing so playback never drops */
    document.addEventListener("click", (ev) => {
      if (!isAudioPlaying()) return;
      if (
        ev.defaultPrevented ||
        ev.button !== 0 ||
        ev.metaKey ||
        ev.ctrlKey ||
        ev.shiftKey ||
        ev.altKey
      ) {
        return;
      }
      const link = ev.target?.closest?.("a[href]");
      if (!link || link.target || link.hasAttribute("download")) return;
      let url;
      try {
        url = new URL(link.href, window.location.origin);
      } catch {
        return;
      }
      if (url.origin !== window.location.origin) return;
      if (
        url.pathname === window.location.pathname &&
        url.search === window.location.search &&
        url.hash
      ) {
        return;
      }
      if (
        url.pathname.startsWith("/web/") ||
        url.pathname.startsWith("/api/") ||
        url.pathname === "/login"
      ) {
        return;
      }

      ev.preventDefault();
      void runPjaxFetch(
        `${url.pathname}${url.search}`,
        { credentials: "same-origin" },
        link.href,
        true,
        url.hash,
        false
      );
    });

    document.addEventListener("submit", (ev) => {
      if (!isAudioPlaying() || ev.defaultPrevented) return;
      const form = ev.target;
      if (!form || form.tagName !== "FORM" || form.target) return;
      const actionRaw = form.getAttribute("action") || window.location.href;
      let actionUrl;
      try {
        actionUrl = new URL(actionRaw, window.location.origin);
      } catch {
        return;
      }
      if (actionUrl.origin !== window.location.origin) return;
      if (
        actionUrl.pathname === "/login" ||
        actionUrl.pathname === "/login/tailscale" ||
        actionUrl.pathname === "/logout"
      ) {
        return;
      }

      const method = (form.getAttribute("method") || "GET").toUpperCase();
      const enctype = (
        form.getAttribute("enctype") || "application/x-www-form-urlencoded"
      ).toLowerCase();
      if (
        method !== "GET" &&
        (method !== "POST" || !enctype.startsWith("application/x-www-form-urlencoded"))
      ) {
        return;
      }

      const fd = new FormData(form);
      if (ev.submitter?.name) {
        fd.append(ev.submitter.name, ev.submitter.value || "");
      }
      const params = new URLSearchParams();
      for (const [key, val] of fd.entries()) {
        if (typeof val === "string") params.append(key, val);
      }

      ev.preventDefault();
      if (method === "GET") {
        const qs = params.toString();
        const getDest = `${actionUrl.pathname}${qs ? `?${qs}` : ""}`;
        void runPjaxFetch(getDest, { credentials: "same-origin" }, getDest, true, "", false);
      } else {
        void runPjaxFetch(
          `${actionUrl.pathname}${actionUrl.search}`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/x-www-form-urlencoded",
              "X-Roche-Csrf": getCsrfToken(),
            },
            body: params.toString(),
            credentials: "same-origin",
            redirect: "follow",
          },
          window.location.href,
          true,
          "",
          true
        );
      }
    });

    window.addEventListener("popstate", () => {
      if (!pjaxNavigated && !isAudioPlaying()) return;
      const targetPathSearch = `${window.location.pathname}${window.location.search}`;
      if (targetPathSearch === lastRenderedUrl) return;
      void runPjaxFetch(
        targetPathSearch,
        { credentials: "same-origin" },
        window.location.href,
        false,
        window.location.hash,
        false
      );
    });

    try {
      const saved = sessionStorage.getItem(STORAGE_KEY);
      if (saved) {
        const parsed = JSON.parse(saved);
        if (parsed?.itemId && parsed?.src) {
          loadTrack(parsed, false);
        }
      }
    } catch {}
  };

  /* --- 2. Reader Scroll Progress --- */
  let readerCleanup = null;
  const initReaderProgress = () => {
    if (readerCleanup) {
      readerCleanup();
      readerCleanup = null;
    }
    const root = document.querySelector(".js-reader-root[data-item-id]");
    if (!root) return;
    const kind = root.getAttribute("data-kind") || "article";
    if (kind !== "article" && kind !== "paper") return;

    const itemId = root.getAttribute("data-item-id");
    let best = Number.parseFloat(root.getAttribute("data-progress") || "0") || 0;
    let lastPosted = best;
    let timer = null;

    const maxScrollablePixels = () =>
      Math.max(0, document.documentElement.scrollHeight - window.innerHeight);

    const measureFraction = () => {
      const maxScroll = maxScrollablePixels();
      if (maxScroll <= 8) return 1.0;
      return Math.max(0, Math.min(1, window.scrollY / maxScroll));
    };

    const checkProgress = (forceFlush) => {
      if (!forceFlush && maxScrollablePixels() <= 8) return;
      const frac = measureFraction();
      if (frac > best) best = frac;
      const crossedNinety = lastPosted < 0.9 && best >= 0.9;
      if (forceFlush || crossedNinety || best - lastPosted >= 0.05) {
        lastPosted = best;
        postProgress(itemId, best, Boolean(forceFlush));
      }
    };

    const onScroll = () => {
      if (timer) return;
      timer = window.setTimeout(() => {
        timer = null;
        checkProgress(false);
      }, 350);
    };

    window.addEventListener("scroll", onScroll, { passive: true });

    const archiveForm = root.querySelector(".js-reader-archive-form");
    archiveForm?.addEventListener("submit", () => {
      const hiddenProg = archiveForm.querySelector("input[name='progress']");
      const live = Math.max(best, measureFraction());
      if (hiddenProg) hiddenProg.value = live.toFixed(4);
    });

    readerCleanup = () => {
      window.removeEventListener("scroll", onScroll);
      if (timer) window.clearTimeout(timer);
    };
  };

  /* --- 3. Interactive k-Way Ranking Board (Explicit Tie-Bucket Model) --- */
  let rankKeyCleanup = null;
  const initRankBoard = () => {
    if (rankKeyCleanup) {
      rankKeyCleanup();
      rankKeyCleanup = null;
    }
    const form = document.querySelector(".js-rank-form");
    if (!form) return;
    const list = form.querySelector(".js-rank-list");
    const hiddenInput = form.querySelector("input[name='ordering_json']");
    const tieAllBtn = form.querySelector(".js-rank-tie-all");
    const skipForm = document.querySelector(".js-rank-skip-form");
    if (!list || !hiddenInput) return;

    const initialCards = Array.from(list.querySelectorAll(".js-rank-card"));
    if (initialCards.length === 0) return;

    let buckets = [];
    for (const [idx, card] of initialCards.entries()) {
      if (idx > 0 && card.getAttribute("data-tied") === "1" && buckets.length > 0) {
        buckets[buckets.length - 1].push(card);
      } else {
        buckets.push([card]);
      }
    }
    let selectedCard = initialCards[0];

    const flatCards = () => buckets.flat();

    const findCoords = (targetCard) => {
      for (let b = 0; b < buckets.length; b++) {
        const i = buckets[b].indexOf(targetCard);
        if (i !== -1) return { b, i };
      }
      return null;
    };

    const syncBoard = () => {
      const groups = [];
      let flatIdx = 0;
      for (const bucket of buckets) {
        if (bucket.length === 0) continue;
        const displayRank = flatIdx + 1;
        const ids = [];
        for (const [idxInBucket, card] of bucket.entries()) {
          if (list.children[flatIdx] !== card) {
            list.insertBefore(card, list.children[flatIdx] || null);
          }
          const tied = idxInBucket > 0;
          card.setAttribute("data-tied", tied ? "1" : "0");
          card.classList.toggle("is-tied", tied);
          card.classList.toggle("is-selected", card === selectedCard);
          const posEl = card.querySelector(".js-rank-pos");
          if (posEl) posEl.textContent = `${tied ? "= " : ""}#${displayRank}`;
          const tieBtn = card.querySelector(".js-card-tie");
          if (tieBtn) {
            tieBtn.disabled = flatIdx === 0;
            tieBtn.textContent = tied ? "Untie" : "= Tie";
            tieBtn.setAttribute("aria-pressed", tied ? "true" : "false");
          }
          ids.push(card.getAttribute("data-entry-id"));
          flatIdx += 1;
        }
        groups.push(ids);
      }
      hiddenInput.value = JSON.stringify(groups);
    };

    const moveUp = (card) => {
      const coords = findCoords(card);
      if (!coords) return;
      if (buckets[coords.b].length > 1) {
        buckets[coords.b].splice(coords.i, 1);
        buckets.splice(coords.b, 0, [card]);
      } else if (coords.b > 0) {
        const tmp = buckets[coords.b - 1];
        buckets[coords.b - 1] = buckets[coords.b];
        buckets[coords.b] = tmp;
      }
      selectedCard = card;
      syncBoard();
    };

    const moveDown = (card) => {
      const coords = findCoords(card);
      if (!coords) return;
      if (buckets[coords.b].length > 1) {
        buckets[coords.b].splice(coords.i, 1);
        buckets.splice(coords.b + 1, 0, [card]);
      } else if (coords.b + 1 < buckets.length) {
        const tmp = buckets[coords.b + 1];
        buckets[coords.b + 1] = buckets[coords.b];
        buckets[coords.b] = tmp;
      }
      selectedCard = card;
      syncBoard();
    };

    const moveToRankSlot = (card, targetZeroBased) => {
      const all = flatCards();
      if (all.length <= 1) return;
      const target = Math.max(0, Math.min(all.length - 1, targetZeroBased));
      const oldFlat = all.indexOf(card);
      const coords = findCoords(card);
      if (!coords || oldFlat === -1) return;

      buckets[coords.b].splice(coords.i, 1);
      if (buckets[coords.b].length === 0) {
        buckets.splice(coords.b, 1);
      }

      let acc = 0;
      let insertIdx = buckets.length;
      for (let idx = 0; idx < buckets.length; idx++) {
        if (target === acc) {
          insertIdx = idx;
          break;
        }
        const nextAcc = acc + buckets[idx].length;
        if (target < nextAcc) {
          insertIdx = oldFlat <= target ? idx + 1 : idx;
          break;
        }
        acc = nextAcc;
      }
      buckets.splice(insertIdx, 0, [card]);
      selectedCard = card;
      syncBoard();
    };

    const toggleTieWithAbove = (card) => {
      const coords = findCoords(card);
      if (!coords) return;
      if (coords.i > 0) {
        const tail = buckets[coords.b].splice(coords.i);
        buckets.splice(coords.b + 1, 0, tail);
      } else if (coords.b > 0) {
        const [cur] = buckets.splice(coords.b, 1);
        buckets[coords.b - 1] = buckets[coords.b - 1].concat(cur);
      }
      selectedCard = card;
      syncBoard();
    };

    const tieAll = () => {
      const all = flatCards();
      if (all.length <= 1) return;
      buckets = buckets.length === 1 ? all.map((c) => [c]) : [all];
      syncBoard();
    };

    list.addEventListener("click", (ev) => {
      const card = ev.target?.closest?.(".js-rank-card");
      if (!card) return;
      selectedCard = card;

      if (ev.target.closest(".js-card-up")) {
        ev.preventDefault();
        moveUp(card);
      } else if (ev.target.closest(".js-card-down")) {
        ev.preventDefault();
        moveDown(card);
      } else if (ev.target.closest(".js-card-tie")) {
        ev.preventDefault();
        toggleTieWithAbove(card);
      } else {
        syncBoard();
      }
    });

    tieAllBtn?.addEventListener("click", (ev) => {
      ev.preventDefault();
      tieAll();
    });

    const onKeyDown = (ev) => {
      if (ev.target?.closest?.("input, textarea, select, [contenteditable]")) return;
      if (ev.metaKey || ev.ctrlKey || ev.altKey) return;

      const cards = flatCards();
      if (cards.length === 0) return;
      let curIdx = cards.indexOf(selectedCard);
      if (curIdx === -1) {
        curIdx = 0;
        selectedCard = cards[0];
      }

      if (ev.key === "j" || (ev.key === "ArrowDown" && !ev.shiftKey)) {
        ev.preventDefault();
        selectedCard = cards[Math.min(cards.length - 1, curIdx + 1)];
        syncBoard();
      } else if (ev.key === "k" || (ev.key === "ArrowUp" && !ev.shiftKey)) {
        ev.preventDefault();
        selectedCard = cards[Math.max(0, curIdx - 1)];
        syncBoard();
      } else if (ev.key === "J" || (ev.key === "ArrowDown" && ev.shiftKey)) {
        ev.preventDefault();
        moveDown(selectedCard);
      } else if (ev.key === "K" || (ev.key === "ArrowUp" && ev.shiftKey)) {
        ev.preventDefault();
        moveUp(selectedCard);
      } else if (ev.key >= "1" && ev.key <= "9") {
        const target = Number.parseInt(ev.key, 10) - 1;
        if (target < cards.length) {
          ev.preventDefault();
          const moved = selectedCard;
          moveToRankSlot(moved, target);
          const afterCards = flatCards();
          const newIdx = afterCards.indexOf(moved);
          if (newIdx !== -1 && newIdx + 1 < afterCards.length) {
            selectedCard = afterCards[newIdx + 1];
            syncBoard();
          }
        }
      } else if (ev.key === "=") {
        ev.preventDefault();
        toggleTieWithAbove(selectedCard);
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
        const url = selectedCard.getAttribute("data-open-url");
        if (url) {
          ev.preventDefault();
          window.open(url, "_blank", "noopener");
        }
      } else if (ev.key === "Enter") {
        if (
          ev.target?.closest?.("a, button, summary, input, textarea, select, [contenteditable]")
        ) {
          return;
        }
        ev.preventDefault();
        if (typeof form.requestSubmit === "function") {
          form.requestSubmit();
        } else {
          form.submit();
        }
      }
    };

    document.addEventListener("keydown", onKeyDown);
    rankKeyCleanup = () => {
      document.removeEventListener("keydown", onKeyDown);
    };
    syncBoard();
  };

  /* --- 4. OPML File Picker Helper --- */
  const initOpmlFileHelper = () => {
    const picker = document.querySelector(".js-opml-file");
    const area = document.querySelector(".js-opml-textarea");
    if (!picker || !area || picker.dataset.bound === "1") return;
    picker.dataset.bound = "1";
    picker.addEventListener("change", () => {
      const file = picker.files?.[0];
      if (!file) return;
      const reader = new FileReader();
      reader.addEventListener("load", () => {
        if (typeof reader.result === "string") area.value = reader.result;
      });
      reader.readAsText(file);
    });
  };

  const hasDirtyFormOrOpenDetails = () => {
    if (document.activeElement?.matches?.("input, textarea, select, [contenteditable]")) {
      return true;
    }
    const curMain = document.getElementById("roche-main");
    if (!curMain) return false;
    if (curMain.querySelector("details[open]")) return true;
    for (const el of curMain.querySelectorAll("input, textarea, select")) {
      if (el.type === "hidden" || el.type === "submit" || el.type === "button") continue;
      if (el.type === "checkbox" || el.type === "radio") {
        if (el.checked !== el.defaultChecked) return true;
      } else if (el.tagName === "SELECT") {
        for (const opt of el.options) {
          if (opt.selected !== opt.defaultSelected) return true;
        }
      } else if (el.value !== el.defaultValue) {
        return true;
      }
    }
    return false;
  };

  /* --- 5. Background Refresh Status Indicator --- */
  let refreshPollTimer = null;

  const initRefreshBanner = () => {
    const banner = document.getElementById("roche-refresh-banner");
    if (!banner) return;
    const label = banner.querySelector(".js-refresh-label");

    const scheduleCheck = (delayMs) => {
      if (refreshPollTimer) clearTimeout(refreshPollTimer);
      refreshPollTimer = setTimeout(async () => {
        refreshPollTimer = null;
        try {
          const resp = await fetch("/web/refresh-status", {
            headers: { Accept: "application/json" },
            credentials: "same-origin",
          });
          if (!resp.ok) return;
          const data = await resp.json();
          if (data?.is_refreshing) {
            banner.hidden = false;
            banner.setAttribute("data-active", "1");
            if (label && Number.isFinite(data.active_refresh_count) && data.active_refresh_count > 1) {
              label.textContent = `Refreshing ${data.active_refresh_count} sources in background…`;
            } else if (label) {
              label.textContent = "Refreshing feeds & metadata in background…";
            }
            scheduleCheck(1500);
          } else {
            const wasActive = banner.getAttribute("data-active") === "1" || !banner.hidden;
            banner.hidden = true;
            banner.setAttribute("data-active", "0");
            if (
              wasActive &&
              !hasDirtyFormOrOpenDetails() &&
              ["/", "/inbox", "/feeds", "/queue"].includes(window.location.pathname)
            ) {
              const curUrl = `${window.location.pathname}${window.location.search}`;
              const r = await fetch(curUrl, { credentials: "same-origin" });
              if (r.ok) {
                const html = await r.text();
                const currentPathSearch = `${window.location.pathname}${window.location.search}`;
                if (curUrl !== currentPathSearch || hasDirtyFormOrOpenDetails()) {
                  return;
                }
                const doc = new DOMParser().parseFromString(html, "text/html");
                const nextMain = doc.getElementById("roche-main");
                const curMain = document.getElementById("roche-main");
                if (nextMain && curMain) {
                  curMain.replaceChildren(...Array.from(nextMain.childNodes));
                  initReaderProgress();
                  initRankBoard();
                  initOpmlFileHelper();
                }
              }
            }
          }
        } catch {
          // Ignore transient status poll errors.
        }
      }, delayMs);
    };

    if (banner.getAttribute("data-active") === "1" || !banner.hidden) {
      scheduleCheck(1200);
    }
  };

  document.addEventListener("submit", (ev) => {
    if (ev.defaultPrevented) return;
    const form = ev.target;
    const action = form?.getAttribute?.("action") || "";
    if (
      action.includes("/refresh") ||
      action === "/web/feeds/create" ||
      action === "/web/feeds/opml" ||
      action === "/web/items/save"
    ) {
      const banner = document.getElementById("roche-refresh-banner");
      if (banner) {
        banner.hidden = false;
        banner.setAttribute("data-active", "1");
      }
    }
  });

  const initPageWidgets = () => {
    initReaderProgress();
    initRankBoard();
    initOpmlFileHelper();
    initRefreshBanner();
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => {
      initAudioPlayer();
      initPageWidgets();
    });
  } else {
    initAudioPlayer();
    initPageWidgets();
  }
})();
