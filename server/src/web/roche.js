/* Roche Web Client — Progressive Enhancement Script
   1. Persistent Podcast Player + seamless PJAX navigation while audio plays
   2. Live Article / Paper scroll progress (>= 90% + archive auto-logs consumed)
   3. Interactive k-way Ranking (explicit tie-bucket model: j/k, J/K, 1-9, =, t, s, o, Enter + touch/mouse) */
(() => {
  "use strict";

  const STORAGE_KEY = "roche_active_audio_v1";
  const SPEEDS = [1, 1.25, 1.5, 1.75, 2];

  const getCsrfToken = () => document.body?.getAttribute("data-csrf") ?? "";

  const isLoginUrl = (rawUrl) => {
    if (!rawUrl) return false;
    try {
      return new URL(rawUrl, window.location.origin).pathname === "/login";
    } catch {
      return false;
    }
  };

  const syncCsrfFromDoc = (doc) => {
    if (!doc?.body?.hasAttribute("data-csrf") || !document.body) return false;
    const nextCsrf = doc.body.getAttribute("data-csrf") || "";
    if (!nextCsrf) return false;
    document.body.setAttribute("data-csrf", nextCsrf);
    for (const input of document.querySelectorAll('input[name="_csrf"]')) {
      input.value = nextCsrf;
    }
    return true;
  };

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

  const showInlinePjaxError = (message, actionLink = null) => {
    hideRefreshBanner();
    const curMain = document.getElementById("roche-main");
    if (!curMain) return;
    curMain.querySelector(".js-pjax-error")?.remove();
    const banner = document.createElement("div");
    banner.className = "notice notice-error js-pjax-error";
    banner.setAttribute("role", "alert");
    banner.textContent =
      message || "Request failed. Audio playback is unaffected — please try again.";
    if (actionLink?.href && actionLink?.label) {
      banner.appendChild(document.createTextNode(" "));
      const link = document.createElement("a");
      link.href = actionLink.href;
      link.textContent = actionLink.label;
      banner.appendChild(link);
    }
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

    let lastPlayTrigger = null;

    const syncPlayButtons = () => {
      const playingId = activeItem && !audio.paused ? activeItem.itemId : null;
      for (const btn of document.querySelectorAll(".js-play-audio")) {
        const isCurrent = Boolean(playingId && btn.getAttribute("data-item-id") === playingId);
        const title = btn.getAttribute("data-title") || "Audio";
        btn.setAttribute("aria-pressed", isCurrent ? "true" : "false");
        btn.setAttribute("aria-label", `${isCurrent ? "Pause" : "Play"} ${title}`);
      }
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
      document.documentElement?.classList.add("has-player-bar");
      if (titleEl) titleEl.textContent = meta.title || "Episode";
      if (subEl) subEl.textContent = meta.feed || "Podcast";
      if (readerLink) {
        readerLink.href = `/reader/${encodeURIComponent(meta.itemId)}`;
        readerLink.setAttribute("aria-label", `Open notes for ${meta.title || "Episode"}`);
      }

      const nextRate = meta.rate || desiredRate || 1;
      if (audio.getAttribute("src") !== meta.src) {
        audio.src = meta.src;
      }
      applyPlaybackRate(nextRate);
      if (speedBtn) {
        speedBtn.textContent = `${desiredRate}x`;
        speedBtn.setAttribute("aria-label", `Playback speed: ${desiredRate}x`);
      }

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

    audio.addEventListener("play", () => {
      syncPlayButtons();
    });

    audio.addEventListener("pause", () => {
      flushActiveProgress(true);
      saveSession();
      syncPlayButtons();
    });

    audio.addEventListener("ended", () => {
      if (!activeItem) return;
      completionSent = true;
      postProgress(activeItem.itemId, 1.0, true);
      saveSession();
      syncPlayButtons();
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
      speedBtn.setAttribute("aria-label", `Playback speed: ${next}x`);
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
      document.documentElement?.classList.remove("has-player-bar");
      saveSession();
      syncPlayButtons();
      if (lastPlayTrigger && lastPlayTrigger.isConnected) {
        lastPlayTrigger.focus({ preventScroll: true });
      } else {
        document.getElementById("roche-main")?.focus({ preventScroll: true });
      }
    });

    document.addEventListener("click", (ev) => {
      const btn = ev.target?.closest?.(".js-play-audio");
      if (!btn) return;
      ev.preventDefault();
      lastPlayTrigger = btn;
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
      syncCsrfFromDoc(doc);
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
        if (resp.redirected && isLoginUrl(resp.url)) {
          window.location.href = resp.url;
          return;
        }
        if (resp.status === 401) {
          if (!isAudioPlaying()) {
            window.location.href = "/login";
          } else {
            showInlinePjaxError("Your session has expired. Audio playback continues —", {
              href: "/login",
              label: "Sign in again",
            });
          }
          return;
        }
        if (resp.status === 403) {
          const errHtml = await resp.text();
          if (controller && activePjaxController !== controller) return;
          const errDoc = new DOMParser().parseFromString(errHtml, "text/html");
          if (syncCsrfFromDoc(errDoc)) {
            showInlinePjaxError(
              "Your security token expired and has been refreshed. Please try your action again."
            );
          } else {
            showInlinePjaxError("Action was rejected (403). Please reload and try again.");
          }
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

    const liveStatus = document.getElementById("rank-live-status");
    const announce = (msg) => {
      if (!liveStatus || !msg) return;
      liveStatus.textContent = "";
      requestAnimationFrame(() => {
        liveStatus.textContent = msg;
      });
    };

    const cardTitle = (card) =>
      card?.getAttribute("data-title") || card?.querySelector("strong")?.textContent?.trim() || "Item";

    const syncBoard = (announcement = "") => {
      const previouslyFocused = document.activeElement;
      const shouldRestoreFocus = Boolean(previouslyFocused && list.contains(previouslyFocused));
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
          const isSelected = card === selectedCard;
          card.setAttribute("data-tied", tied ? "1" : "0");
          card.classList.toggle("is-tied", tied);
          card.classList.toggle("is-selected", isSelected);
          if (isSelected) {
            card.setAttribute("aria-current", "true");
          } else {
            card.removeAttribute("aria-current");
          }
          const posEl = card.querySelector(".js-rank-pos");
          if (posEl) posEl.textContent = `${tied ? "= " : ""}#${displayRank}`;
          const tieBtn = card.querySelector(".js-card-tie");
          if (tieBtn) {
            const title = cardTitle(card);
            tieBtn.disabled = flatIdx === 0;
            tieBtn.replaceChildren();
            if (tied) {
              tieBtn.append(document.createTextNode("Untie"));
              tieBtn.setAttribute("aria-label", `Untie ${title} from above`);
            } else {
              const eqSpan = document.createElement("span");
              eqSpan.setAttribute("aria-hidden", "true");
              eqSpan.textContent = "=";
              tieBtn.append(eqSpan, document.createTextNode(" Tie"));
              tieBtn.setAttribute("aria-label", `Tie ${title} with above`);
            }
            tieBtn.setAttribute("aria-pressed", tied ? "true" : "false");
          }
          ids.push(card.getAttribute("data-entry-id"));
          flatIdx += 1;
        }
        groups.push(ids);
      }
      if (tieAllBtn) {
        const allTied = buckets.length === 1 && flatIdx > 1;
        tieAllBtn.setAttribute("aria-pressed", allTied ? "true" : "false");
        tieAllBtn.replaceChildren();
        if (allTied) {
          tieAllBtn.append(document.createTextNode("Untie All"));
        } else {
          const eqSpan = document.createElement("span");
          eqSpan.setAttribute("aria-hidden", "true");
          eqSpan.textContent = "=";
          tieAllBtn.append(eqSpan, document.createTextNode(" Tie All"));
        }
      }
      hiddenInput.value = JSON.stringify(groups);
      if (shouldRestoreFocus && document.activeElement !== previouslyFocused) {
        if (!previouslyFocused.disabled && previouslyFocused.isConnected) {
          previouslyFocused.focus({ preventScroll: true });
        } else if (selectedCard?.isConnected) {
          selectedCard.focus({ preventScroll: true });
        }
      }
      if (announcement) announce(announcement);
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
      const newPos = flatCards().indexOf(card) + 1;
      syncBoard(`Moved ${cardTitle(card)} to position ${newPos}`);
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
      const newPos = flatCards().indexOf(card) + 1;
      syncBoard(`Moved ${cardTitle(card)} to position ${newPos}`);
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
      syncBoard(`Moved ${cardTitle(card)} to position ${target + 1}`);
    };

    const toggleTieWithAbove = (card) => {
      const coords = findCoords(card);
      if (!coords) return;
      let msg = "";
      if (coords.i > 0) {
        const tail = buckets[coords.b].splice(coords.i);
        buckets.splice(coords.b + 1, 0, tail);
        msg = `Untied ${cardTitle(card)} from above`;
      } else if (coords.b > 0) {
        const [cur] = buckets.splice(coords.b, 1);
        buckets[coords.b - 1] = buckets[coords.b - 1].concat(cur);
        msg = `Tied ${cardTitle(card)} with above`;
      }
      selectedCard = card;
      syncBoard(msg);
    };

    const tieAll = () => {
      const all = flatCards();
      if (all.length <= 1) return;
      const untying = buckets.length === 1;
      buckets = untying ? all.map((c) => [c]) : [all];
      syncBoard(untying ? "Untied all items" : "Tied all items");
    };

    list.addEventListener("focusin", (ev) => {
      const card = ev.target?.closest?.(".js-rank-card");
      if (card && card !== selectedCard) {
        selectedCard = card;
        syncBoard();
      }
    });

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
      if (!form.contains(ev.target)) return;
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
        syncBoard(`Selected ${cardTitle(selectedCard)}`);
        if (ev.target.classList?.contains("js-rank-card")) {
          selectedCard.focus({ preventScroll: true });
        }
      } else if (ev.key === "k" || (ev.key === "ArrowUp" && !ev.shiftKey)) {
        ev.preventDefault();
        selectedCard = cards[Math.max(0, curIdx - 1)];
        syncBoard(`Selected ${cardTitle(selectedCard)}`);
        if (ev.target.classList?.contains("js-rank-card")) {
          selectedCard.focus({ preventScroll: true });
        }
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
    const active = document.activeElement;
    if (active?.matches?.("input, textarea, select, [contenteditable]")) {
      return true;
    }
    const curMain = document.getElementById("roche-main");
    if (!curMain) return false;
    if (active && active !== curMain && curMain.contains(active)) {
      return true;
    }
    if (curMain.querySelector("details[open]:not([data-default-open])")) return true;
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
          if (!resp.ok || (resp.redirected && isLoginUrl(resp.url))) return;
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
              if (r.redirected && isLoginUrl(r.url)) {
                window.location.href = r.url;
                return;
              }
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
                  syncCsrfFromDoc(doc);
                  curMain.replaceChildren(...Array.from(nextMain.childNodes));
                  initPageWidgets();
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

  /* --- 6. Theme Toggle, Masthead Search & Sleeve Drawers --- */
  const THEME_STORAGE_KEY = "roche_theme_v1";

  const applySavedTheme = () => {
    try {
      const saved = localStorage.getItem(THEME_STORAGE_KEY);
      if (saved === "dark" || saved === "light") {
        document.documentElement.setAttribute("data-theme", saved);
      }
    } catch {}
  };
  applySavedTheme();

  const initShellControls = () => {
    const themeBtn = document.getElementById("roche-theme-toggle");
    if (themeBtn && themeBtn.dataset.bound !== "1") {
      themeBtn.dataset.bound = "1";
      const isCurrentlyDark = () => {
        const attr = document.documentElement.getAttribute("data-theme");
        if (attr === "dark") return true;
        if (attr === "light") return false;
        return Boolean(window.matchMedia?.("(prefers-color-scheme: dark)")?.matches);
      };
      const syncThemeLabel = () => {
        const dark = isCurrentlyDark();
        themeBtn.setAttribute("aria-label", dark ? "Switch to light theme" : "Switch to dark theme");
        themeBtn.setAttribute("title", dark ? "Switch to light theme" : "Switch to dark theme");
      };
      syncThemeLabel();
      themeBtn.addEventListener("click", () => {
        const next = isCurrentlyDark() ? "light" : "dark";
        document.documentElement.setAttribute("data-theme", next);
        try {
          localStorage.setItem(THEME_STORAGE_KEY, next);
        } catch {}
        syncThemeLabel();
      });
    }

    const masthead = document.getElementById("roche-masthead");
    const searchToggle = document.getElementById("roche-search-toggle");
    const searchInput = document.getElementById("roche-global-search");
    if (searchToggle && masthead && searchToggle.dataset.bound !== "1") {
      searchToggle.dataset.bound = "1";
      searchToggle.addEventListener("click", () => {
        const open = masthead.classList.toggle("is-search-open");
        searchToggle.setAttribute("aria-expanded", open ? "true" : "false");
        if (open) searchInput?.focus();
      });
    }

    if (document.body && document.body.dataset.globalKeysBound !== "1") {
      document.body.dataset.globalKeysBound = "1";
      document.addEventListener("keydown", (ev) => {
        if (ev.key !== "/" || ev.metaKey || ev.ctrlKey || ev.altKey) return;
        const target = ev.target;
        if (target?.matches?.("input, textarea, select, [contenteditable]")) return;
        if (document.getElementById("roche-rank-board")) return;
        const input = document.getElementById("roche-global-search");
        if (!input) return;
        ev.preventDefault();
        const hdr = document.getElementById("roche-masthead");
        if (hdr && window.getComputedStyle(input).display === "none") {
          hdr.classList.add("is-search-open");
          document.getElementById("roche-search-toggle")?.setAttribute("aria-expanded", "true");
        }
        input.focus();
        input.select();
      });
    }

    const refreshForm = document.getElementById("roche-masthead-refresh");
    if (refreshForm && refreshForm.dataset.bound !== "1") {
      refreshForm.dataset.bound = "1";
      refreshForm.addEventListener("submit", () => {
        const retInput = refreshForm.querySelector('input[name="return_to"]');
        if (retInput) {
          retInput.value = `${window.location.pathname}${window.location.search}`;
        }
      });
    }
  };

  const initSleeveDrawers = () => {
    for (const container of document.querySelectorAll(".sleeve-actions")) {
      if (container.dataset.bound === "1") continue;
      container.dataset.bound = "1";
      const drawers = Array.from(container.querySelectorAll("details.album-inline-details"));
      for (const drawer of drawers) {
        drawer.addEventListener("toggle", () => {
          if (!drawer.open) return;
          for (const other of drawers) {
            if (other !== drawer && other.open) other.open = false;
          }
        });
      }
    }
  };

  const initSidebarFeedFilter = () => {
    const input = document.querySelector(".sidebar-feed-filter");
    const list = document.getElementById("proto-feed-list") || document.getElementById("roche-sidebar-feeds");
    if (!input || !list || input.dataset.bound === "1") return;
    input.dataset.bound = "1";
    input.addEventListener("input", () => {
      const q = input.value.trim().toLowerCase();
      for (const li of list.children) {
        const name = (li.getAttribute("data-feed-name") || li.textContent || "").toLowerCase();
        li.hidden = Boolean(q && !name.includes(q));
      }
    });
  };

  const initFaviconFallbacks = () => {
    for (const img of document.querySelectorAll(".feed-favicon img")) {
      if (img.dataset.bound === "1") continue;
      img.dataset.bound = "1";
      const markFallback = () => img.closest(".feed-favicon")?.classList.add("is-fallback");
      img.addEventListener("error", markFallback, { once: true });
      if (img.complete && img.naturalWidth === 0) markFallback();
    }
  };

  const initReaderExtras = () => {
    const root = document.querySelector(".js-reader-root");
    if (!root) return;

    // 1. Hairline reading progress bar
    let bar = document.getElementById("roche-read-progress");
    if (!bar) {
      bar = document.createElement("div");
      bar.id = "roche-read-progress";
      bar.className = "reader-progress-bar";
      bar.setAttribute("aria-hidden", "true");
      document.body.prepend(bar);
    }
    const updateBar = () => {
      const el = document.documentElement;
      const scrollable = el.scrollHeight - el.clientHeight;
      const pct = scrollable > 0 ? Math.max(0, Math.min(100, (window.scrollY / scrollable) * 100)) : 0;
      bar.style.width = `${pct.toFixed(1)}%`;
    };
    updateBar();
    if (document.body.dataset.readBarBound !== "1") {
      document.body.dataset.readBarBound = "1";
      window.addEventListener("scroll", () => {
        const b = document.getElementById("roche-read-progress");
        if (!b || !document.querySelector(".js-reader-root")) {
          if (b) b.remove();
          return;
        }
        const el = document.documentElement;
        const scrollable = el.scrollHeight - el.clientHeight;
        const pct = scrollable > 0 ? Math.max(0, Math.min(100, (window.scrollY / scrollable) * 100)) : 0;
        b.style.width = `${pct.toFixed(1)}%`;
      }, { passive: true });
    }

    // 2. Reader font size preference (A- / A+, CSP-safe data attribute)
    const SIZE_KEY = "roche_reader_font_v1";
    const MAX_SIZE_IDX = 3;
    const prose = root.querySelector(".reader-prose");
    if (prose) {
      let idx = 1;
      try {
        const saved = Number.parseInt(localStorage.getItem(SIZE_KEY) || "1", 10);
        if (saved >= 0 && saved <= MAX_SIZE_IDX) idx = saved;
      } catch {}
      const applySize = () => {
        root.setAttribute("data-reader-size", String(idx));
      };
      applySize();
      for (const btn of document.querySelectorAll(".js-font-dec")) {
        if (btn.dataset.bound === "1") continue;
        btn.dataset.bound = "1";
        btn.addEventListener("click", () => {
          idx = Math.max(0, idx - 1);
          try { localStorage.setItem(SIZE_KEY, String(idx)); } catch {}
          applySize();
        });
      }
      for (const btn of document.querySelectorAll(".js-font-inc")) {
        if (btn.dataset.bound === "1") continue;
        btn.dataset.bound = "1";
        btn.addEventListener("click", () => {
          idx = Math.min(MAX_SIZE_IDX, idx + 1);
          try { localStorage.setItem(SIZE_KEY, String(idx)); } catch {}
          applySize();
        });
      }
    }

    // 3. Auto-build Table of Contents from h2/h3 in prose
    const tocList = document.getElementById("roche-reader-toc");
    const tocGroup = document.getElementById("roche-reader-toc-group");
    if (tocList && prose && tocList.dataset.built !== "1") {
      tocList.dataset.built = "1";
      const headings = Array.from(prose.querySelectorAll("h2, h3")).filter(
        (h) => (h.textContent || "").trim().length > 0
      );
      if (headings.length >= 2) {
        headings.forEach((h, i) => {
          if (!h.id) h.id = `sec-${i + 1}`;
          const li = document.createElement("li");
          if (h.tagName === "H3") li.className = "toc-sub";
          const a = document.createElement("a");
          a.href = `#${h.id}`;
          a.textContent = h.textContent.trim();
          li.appendChild(a);
          tocList.appendChild(li);
        });
        if (tocGroup) tocGroup.hidden = false;
      } else if (tocGroup) {
        tocGroup.hidden = true;
      }
    }

    // 4. Lightweight warm-editorial syntax highlighting for <pre><code> blocks
    if (prose) {
      const KW = new Set([
        "def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or",
        "import", "from", "as", "with", "try", "except", "finally", "raise", "pass", "break",
        "continue", "lambda", "yield", "async", "await", "None", "True", "False", "print",
        "fn", "let", "mut", "pub", "struct", "enum", "impl", "trait", "match", "use", "mod",
        "const", "static", "type", "where", "ref", "move", "self", "Self", "super", "crate",
        "function", "var", "new", "this", "typeof", "export", "default", "switch", "case",
        "int", "void", "char", "bool", "float", "double", "unsigned", "signed", "long", "short",
        "sizeof", "typedef", "include", "define", "null", "true", "false", "SELECT", "FROM",
        "WHERE", "JOIN", "ORDER", "GROUP", "BY", "LIMIT", "INSERT", "UPDATE", "DELETE"
      ]);
      const TOKEN_RE =
        /(#[^\n]*|\/\/[^\n]*|\/\*[\s\S]*?\*\/|"""[\s\S]*?"""|'''[\s\S]*?'''|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|\b0x[0-9a-fA-F_]+\b|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)/g;

      for (const codeEl of prose.querySelectorAll("pre > code")) {
        if (codeEl.dataset.highlighted === "1") continue;
        codeEl.dataset.highlighted = "1";
        const src = codeEl.textContent || "";
        if (src.length > 24000) continue;
        const frag = document.createDocumentFragment();
        let last = 0;
        let m;
        TOKEN_RE.lastIndex = 0;
        while ((m = TOKEN_RE.exec(src)) !== null) {
          if (m.index > last) frag.appendChild(document.createTextNode(src.slice(last, m.index)));
          const tok = m[0];
          let cls = "";
          if (tok.startsWith("#") || tok.startsWith("//") || tok.startsWith("/*")) {
            cls = "syn-cm";
          } else if (tok.startsWith('"') || tok.startsWith("'")) {
            cls = "syn-str";
          } else if (/^\d/.test(tok)) {
            cls = "syn-num";
          } else if (KW.has(tok)) {
            cls = "syn-kw";
          } else if (src[TOKEN_RE.lastIndex] === "(") {
            cls = "syn-fn";
          }
          if (cls) {
            const sp = document.createElement("span");
            sp.className = cls;
            sp.textContent = tok;
            frag.appendChild(sp);
          } else {
            frag.appendChild(document.createTextNode(tok));
          }
          last = TOKEN_RE.lastIndex;
        }
        if (last < src.length) frag.appendChild(document.createTextNode(src.slice(last)));
        codeEl.replaceChildren(frag);
      }
    }

    // 5. Auto-finish sync at bottom of article + click-to-toggle Finished state
    const finishForm = root.querySelector(".js-finish-toggle-form");
    const finishBtn = root.querySelector(".js-finish-toggle-btn, .js-proto-finish-toggle");
    if (finishBtn && finishBtn.dataset.bound !== "1") {
      finishBtn.dataset.bound = "1";
      let userUntoggled = root.getAttribute("data-suppress-auto-finish") === "1";
      const itemId = root.getAttribute("data-item-id") || "";
      const setFinishedUi = (done) => {
        finishBtn.classList.toggle("is-done", done);
        finishBtn.setAttribute("aria-pressed", done ? "true" : "false");
        finishBtn.textContent = done ? "✓ Finished" : "Mark Finished";
        finishBtn.setAttribute(
          "title",
          done ? "Marked finished — click to unfinish" : "Mark this item as finished"
        );
        if (finishForm) {
          const nextAction = done
            ? finishForm.getAttribute("data-unfinish-action")
            : finishForm.getAttribute("data-finish-action");
          if (nextAction) finishForm.setAttribute("action", nextAction);
        }
      };
      const checkBottomFinish = () => {
        if (userUntoggled || !document.body.contains(finishBtn)) return;
        const kind = root.getAttribute("data-kind") || "article";
        if (kind !== "article" && kind !== "paper") return;
        const el = document.documentElement;
        const scrollable = el.scrollHeight - el.clientHeight;
        if (scrollable <= 8) return;
        if (window.scrollY / scrollable >= 0.92 && !finishBtn.classList.contains("is-done")) {
          setFinishedUi(true);
          if (itemId && finishForm) {
            postProgress(itemId, 1.0, false);
          }
        }
      };
      window.addEventListener("scroll", checkBottomFinish, { passive: true });
      if (!finishForm) {
        finishBtn.addEventListener("click", () => {
          const next = !finishBtn.classList.contains("is-done");
          userUntoggled = !next;
          setFinishedUi(next);
        });
      }
    }
  };

  const initOptimisticStars = () => {
    if (document.body?.dataset.starDelegationBound === "1") return;
    if (document.body) document.body.dataset.starDelegationBound = "1";
    document.addEventListener("click", (ev) => {
      const btn = ev.target?.closest?.(".inbox-star-btn, .reader-star-btn, .js-star-toggle");
      if (!btn) return;
      const form = btn.closest("form");
      const nextStarred = btn.getAttribute("aria-pressed") !== "true";
      const syncBtn = (el) => {
        el.classList.toggle("is-starred", nextStarred);
        el.setAttribute("aria-pressed", nextStarred ? "true" : "false");
        const glyphHost = el.querySelector("span[aria-hidden]") || el;
        glyphHost.textContent = nextStarred ? "★" : "☆";
      };

      // On Reader, keep header star and sticky rail star in sync
      if (btn.classList.contains("reader-star-btn") || btn.classList.contains("js-star-toggle")) {
        for (const el of document.querySelectorAll(".reader-star-btn, .js-star-toggle")) {
          syncBtn(el);
        }
      } else {
        syncBtn(btn);
      }

      if (!form) return;
      const action = form.getAttribute("action") || "";
      if (!action.startsWith("/web/")) return;
      ev.preventDefault();
      const csrf = getCsrfToken();
      const fd = new FormData(form);
      const params = new URLSearchParams();
      for (const [k, v] of fd.entries()) {
        if (typeof v === "string") params.append(k, v);
      }
      fetch(action, {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "X-Roche-Csrf": csrf,
        },
        body: params.toString(),
        credentials: "same-origin",
      }).catch(() => {});
    });
  };

  const initFeedsFilter = () => {
    const list = document.getElementById("roche-feeds-list");
    if (!list || list.dataset.bound === "1") return;
    list.dataset.bound = "1";
    const input = document.getElementById("roche-feeds-search");
    const kindSelect = document.getElementById("roche-feeds-kind");
    const pills = Array.from(document.querySelectorAll(".js-feeds-pill"));
    let activeStatus = "all";

    const apply = () => {
      const q = (input?.value || "").trim().toLowerCase();
      const activeKind = (kindSelect?.value || "all").toLowerCase();
      for (const row of list.children) {
        const hay = (row.getAttribute("data-feed-search") || "").toLowerCase();
        const kind = (row.getAttribute("data-feed-kind") || "").toLowerCase();
        const paused = row.getAttribute("data-feed-paused") === "1";
        const hasErr = row.getAttribute("data-feed-error") === "1";
        const matchStatus =
          activeStatus === "all" ||
          (activeStatus === "active" && !paused) ||
          (activeStatus === "errors" && hasErr) ||
          (activeStatus === "paused" && paused);
        const matchKind = activeKind === "all" || kind === activeKind;
        const matchQuery = !q || hay.includes(q);
        row.hidden = !(matchStatus && matchKind && matchQuery);
      }
    };

    input?.addEventListener("input", apply);
    kindSelect?.addEventListener("change", apply);
    for (const pill of pills) {
      pill.addEventListener("click", () => {
        activeStatus = pill.getAttribute("data-status") || "all";
        for (const p of pills) {
          const isCur = p === pill;
          p.classList.toggle("is-active", isCur);
          p.setAttribute("aria-pressed", isCur ? "true" : "false");
        }
        apply();
      });
    }
  };

  const initListFilter = () => {
    const tbody = document.getElementById("roche-ranked-tbody");
    if (!tbody || tbody.dataset.bound === "1") return;
    tbody.dataset.bound = "1";
    const input = document.getElementById("roche-list-search");
    const pills = Array.from(document.querySelectorAll(".js-tier-pill"));
    const distRows = Array.from(document.querySelectorAll(".js-tier-dist-btn"));
    let activeTier = "all";

    const syncButtons = () => {
      for (const p of pills) {
        const isCur = (p.getAttribute("data-tier") || "all") === activeTier;
        p.classList.toggle("is-active", isCur);
        p.setAttribute("aria-pressed", isCur ? "true" : "false");
      }
      for (const r of distRows) {
        const isCur = (r.getAttribute("data-tier") || "") === activeTier;
        r.classList.toggle("is-active", isCur);
        r.setAttribute("aria-pressed", isCur ? "true" : "false");
      }
    };

    const apply = () => {
      const q = (input?.value || "").trim().toLowerCase();
      for (const row of tbody.children) {
        const hay = (row.getAttribute("data-work-search") || "").toLowerCase();
        const tier = row.getAttribute("data-tier") || "";
        const matchTier = activeTier === "all" || tier === activeTier;
        const matchQuery = !q || hay.includes(q);
        row.hidden = !(matchTier && matchQuery);
      }
    };

    input?.addEventListener("input", apply);
    for (const pill of pills) {
      pill.addEventListener("click", () => {
        activeTier = pill.getAttribute("data-tier") || "all";
        syncButtons();
        apply();
      });
    }
    for (const btn of distRows) {
      btn.addEventListener("click", () => {
        const t = btn.getAttribute("data-tier") || "all";
        activeTier = activeTier === t ? "all" : t;
        syncButtons();
        apply();
      });
    }
  };

  const initListsViewToggle = () => {
    const root = document.getElementById("roche-lists-main");
    const btns = Array.from(document.querySelectorAll(".js-lists-view-btn"));
    if (!root || btns.length === 0 || root.dataset.viewBound === "1") return;
    root.dataset.viewBound = "1";
    const VIEW_KEY = "roche_lists_view_v1";

    const applyView = (mode) => {
      const normalized = mode === "compact" ? "compact" : "previews";
      root.setAttribute("data-lists-view", normalized);
      for (const b of btns) {
        const active = b.getAttribute("data-view") === normalized;
        b.classList.toggle("is-active", active);
        b.setAttribute("aria-pressed", active ? "true" : "false");
      }
    };

    try {
      const saved = localStorage.getItem(VIEW_KEY);
      if (saved === "compact" || saved === "previews") applyView(saved);
    } catch {}

    for (const b of btns) {
      b.addEventListener("click", () => {
        const mode = b.getAttribute("data-view") || "previews";
        applyView(mode);
        try { localStorage.setItem(VIEW_KEY, mode); } catch {}
      });
    }

    // Inline "+ New List" toggle button next to Your Lists heading
    const newListBtn = document.querySelector(".js-new-list-toggle");
    const newListForm = document.getElementById("roche-new-list-form");
    if (newListBtn && newListForm && newListBtn.dataset.bound !== "1") {
      newListBtn.dataset.bound = "1";
      newListBtn.addEventListener("click", () => {
        const opening = newListForm.hidden;
        newListForm.hidden = !opening;
        newListBtn.setAttribute("aria-expanded", opening ? "true" : "false");
        if (opening) newListForm.querySelector("input")?.focus();
      });
    }

    // Close open Draw popovers on outside click or Escape
    if (document.body && document.body.dataset.drawPopoverBound !== "1") {
      document.body.dataset.drawPopoverBound = "1";
      document.addEventListener("click", (ev) => {
        for (const d of document.querySelectorAll(".draw-badge-details[open]")) {
          if (!d.contains(ev.target)) d.open = false;
        }
      });
      document.addEventListener("keydown", (ev) => {
        if (ev.key !== "Escape") return;
        for (const d of document.querySelectorAll(".draw-badge-details[open]")) {
          d.open = false;
        }
      });
    }
  };

  const initPageWidgets = () => {
    initReaderProgress();
    initReaderExtras();
    initRankBoard();
    initOpmlFileHelper();
    initRefreshBanner();
    initSleeveDrawers();
    initSidebarFeedFilter();
    initFeedsFilter();
    initListFilter();
    initListsViewToggle();
    initFaviconFallbacks();
    initOptimisticStars();
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => {
      initShellControls();
      initAudioPlayer();
      initPageWidgets();
    });
  } else {
    initShellControls();
    initAudioPlayer();
    initPageWidgets();
  }
})();
