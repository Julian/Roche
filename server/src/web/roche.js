/* Roche Web Client — Progressive Enhancement Script
   1. Persistent Podcast Player + seamless PJAX navigation while audio plays
   2. Live Article / Paper scroll progress (>= 98% or 4s short-article dwell auto-Finishes; Dismiss never logs Finished)
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

  const postProgress = (itemId, progress, keepalive = false, source = null) => {
    if (!itemId || !Number.isFinite(progress)) return;
    const clamped = Math.max(0, Math.min(1, progress));
    const csrf = getCsrfToken();
    let body = `_csrf=${encodeURIComponent(csrf)}&progress=${clamped.toFixed(4)}`;
    if (source) {
      body += `&source=${encodeURIComponent(source)}`;
    }
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

  const WANT_TO_READ_COLLECTION_ID = "2ef71101-a716-5331-9515-cb7b5ce8b3c3";
  let syncPlayButtonsFn = null;
  let isAudioActiveForItemFn = null;
  let navigateViaPjaxFn = null;
  let applyBoardFilterFn = null;

  const markReaderUiFinished = (itemId, toastLabel = "Marked finished", opts = {}) => {
    const root = document.querySelector(".js-reader-root");
    const isBoardReader = Boolean(
      opts.isBoardReader ?? (root?.getAttribute("data-board-reader") === "1")
    );
    if (root && root.getAttribute("data-item-id") === itemId) {
      root.setAttribute("data-finished", "1");
      root.setAttribute("data-dismissed", "0");
      const finishForm = root.querySelector(".js-finish-toggle-form");
      const finishBtn = root.querySelector(".js-finish-toggle-btn");
      if (finishForm && finishBtn) {
        const unfinishAction =
          finishForm.getAttribute("data-unfinish-action") ||
          `/web/items/${encodeURIComponent(itemId)}/unfinish`;
        finishForm.setAttribute("data-unfinish-action", unfinishAction);
        finishForm.setAttribute("action", unfinishAction);
        finishForm.querySelector('input[name="action"]')?.remove();
        if (isBoardReader && !finishForm.querySelector('input[name="requeue"]')) {
          const reqInput = document.createElement("input");
          reqInput.type = "hidden";
          reqInput.name = "requeue";
          reqInput.value = "0";
          finishForm.appendChild(reqInput);
        }
        finishForm.setAttribute(
          "data-confirm",
          "Undo Finished and remove this item from your Diary?"
        );
        finishBtn.classList.remove("btn-primary");
        finishBtn.classList.add("btn-small", "btn-ghost", "is-done");
        finishBtn.setAttribute("aria-pressed", "true");
        finishBtn.setAttribute("title", "Marked finished — click to undo");
        finishBtn.textContent = "✓ Finished";
      }
      if (isBoardReader) {
        const layout = root.closest(".reader-layout") || document;
        root.querySelector(".reader-end-rank-btn")?.closest("form")?.remove();
        if (opts.rankHref) {
          const endPrimary = root.querySelector(".reader-end-primary");
          if (endPrimary && !endPrimary.querySelector('a[href*="/rank/"]')) {
            const cmp = document.createElement("a");
            cmp.className = "btn btn-small btn-ghost js-auto-finish-compare";
            cmp.href = opts.rankHref;
            cmp.textContent = "Compare →";
            endPrimary.appendChild(cmp);
          }
          const railLinks = layout.querySelector(".js-reader-rail-links");
          if (railLinks && !railLinks.querySelector(".js-reader-compare-link")) {
            if (railLinks.querySelector("a")) {
              railLinks.appendChild(document.createElement("br"));
            }
            const railCmp = document.createElement("a");
            railCmp.className = "reader-rail-link js-reader-compare-link js-auto-finish-compare";
            railCmp.href = opts.rankHref;
            railCmp.textContent = "Compare →";
            railLinks.appendChild(railCmp);
          }
          const mobileLinks = layout.querySelector(".js-reader-mobile-links");
          if (mobileLinks && !mobileLinks.querySelector(".js-reader-compare-link")) {
            const mobCmp = document.createElement("a");
            mobCmp.className = "reader-rail-link js-reader-compare-link js-auto-finish-compare";
            mobCmp.href = opts.rankHref;
            mobCmp.textContent = "Compare →";
            mobileLinks.appendChild(mobCmp);
          }
        }
        if (opts.entryHref) {
          const railLinks = layout.querySelector(".js-reader-rail-links");
          if (railLinks && !railLinks.querySelector(".js-reader-history-link")) {
            if (railLinks.querySelector("a")) {
              railLinks.appendChild(document.createElement("br"));
            }
            const railHist = document.createElement("a");
            railHist.className = "reader-rail-link js-reader-history-link";
            railHist.href = opts.entryHref;
            railHist.textContent = "History & Lists →";
            railLinks.appendChild(railHist);
          }
          const mobileLinks = layout.querySelector(".js-reader-mobile-links");
          if (mobileLinks && !mobileLinks.querySelector(".js-reader-history-link")) {
            const mobHist = document.createElement("a");
            mobHist.className = "reader-rail-link js-reader-history-link";
            mobHist.href = opts.entryHref;
            mobHist.textContent = "History & Lists →";
            mobileLinks.appendChild(mobHist);
          }
        }
        // Finishing clears Want to Read on the server; flip any Reader "In Queue" controls back to "+ Queue".
        for (const qForm of layout.querySelectorAll(".js-reader-queue-form, .js-reader-queue-rail-form")) {
          if (opts.queueAction) {
            qForm.setAttribute("action", opts.queueAction);
            qForm.querySelector('input[name="action"]')?.remove();
            if (!qForm.querySelector('input[name="list_slug_or_id"]')) {
              const slugIn = document.createElement("input");
              slugIn.type = "hidden";
              slugIn.name = "list_slug_or_id";
              slugIn.value = WANT_TO_READ_COLLECTION_ID;
              qForm.appendChild(slugIn);
            }
          }
          const qBtn = qForm.querySelector("button[type='submit']");
          if (!qBtn) continue;
          if (qForm.classList.contains("js-reader-queue-form")) {
            qBtn.classList.remove("is-in-queue");
            qBtn.textContent = "+ Queue";
          } else {
            const itemTitle = qBtn.getAttribute("data-title") || "story";
            qBtn.setAttribute("title", "Add to Queue");
            qBtn.setAttribute("aria-label", `Add ${itemTitle} to Queue`);
            const paths = qBtn.querySelectorAll("svg path");
            if (paths.length >= 2) {
              paths[1].setAttribute("d", "M8 5.2v3.6M6.2 7h3.6");
            }
          }
        }
      }
      const byline = root.querySelector(".js-reader-byline");
      if (byline) {
        let badge = byline.querySelector(".js-reader-state-badge");
        if (!badge) {
          badge = document.createElement("span");
          badge.className = "badge badge-finished js-reader-state-badge";
          byline.prepend(badge);
        } else {
          badge.className = "badge badge-finished js-reader-state-badge";
        }
        badge.textContent = "✓ Finished";
      }
    }
    const unfinishUrl = `/web/items/${encodeURIComponent(itemId)}/unfinish`;
    showToast(toastLabel, {
      onUndo: () => {
        const csrf = getCsrfToken();
        const params = new URLSearchParams();
        params.append("_csrf", csrf);
        if (isBoardReader) {
          params.append("requeue", "0");
        }
        fetch(unfinishUrl, {
          method: "POST",
          headers: {
            "Content-Type": "application/x-www-form-urlencoded",
            "X-Roche-Csrf": csrf,
          },
          body: params.toString(),
          credentials: "same-origin",
        })
          .then((resp) => {
            if (!resp.ok || isLoginUrl(resp.url)) {
              showToast("Could not undo Finished — please try again.", { isError: true });
              return;
            }
            const curRoot = document.querySelector(".js-reader-root");
            if (curRoot && curRoot.getAttribute("data-item-id") === itemId) {
              curRoot.setAttribute("data-finished", "0");
              curRoot.setAttribute("data-suppress-auto-finish", "1");
              const formEl = curRoot.querySelector(".js-finish-toggle-form");
              const btnEl = curRoot.querySelector(".js-finish-toggle-btn");
              if (formEl && btnEl) {
                const finishAction = formEl.getAttribute("data-finish-action");
                if (finishAction) {
                  formEl.setAttribute("action", finishAction);
                  if (
                    finishAction.endsWith("/promote") &&
                    !formEl.querySelector('input[name="action"]')
                  ) {
                    const actInput = document.createElement("input");
                    actInput.type = "hidden";
                    actInput.name = "action";
                    actInput.value = "finish";
                    formEl.appendChild(actInput);
                  }
                }
                formEl.querySelector('input[name="requeue"]')?.remove();
                formEl.removeAttribute("data-confirm");
                btnEl.classList.remove("btn-small", "btn-ghost", "is-done");
                btnEl.classList.add("btn-primary");
                btnEl.setAttribute("aria-pressed", "false");
                btnEl.setAttribute("title", "Mark this item as finished and log to Diary");
                btnEl.textContent = "✓ Mark Finished";
              }
              curRoot.querySelector(".js-reader-state-badge")?.remove();
              const curLayout = curRoot.closest(".reader-layout") || document;
              for (const el of curLayout.querySelectorAll(".js-auto-finish-compare")) {
                if (el.previousSibling?.nodeName === "BR") el.previousSibling.remove();
                el.remove();
              }
            }
            showToast("Undid Finished");
          })
          .catch(() => {
            showToast("Network error while undoing Finished.", { isError: true });
          });
      },
    });
  };

  const showToast = (message, opts = {}) => {
    const region = document.getElementById("roche-toast-region");
    if (!region || !message) return;
    const toast = document.createElement("div");
    toast.className = `roche-toast${opts.isError ? " is-error" : ""}`;
    toast.setAttribute("role", opts.isError ? "alert" : "status");

    const text = document.createElement("span");
    text.className = "roche-toast-msg";
    text.textContent = message;
    toast.appendChild(text);

    let dismissTimer = null;
    const dismiss = () => {
      if (dismissTimer) window.clearTimeout(dismissTimer);
      toast.remove();
    };

    if (typeof opts.onUndo === "function") {
      const undoBtn = document.createElement("button");
      undoBtn.type = "button";
      undoBtn.className = "btn btn-small roche-toast-undo";
      undoBtn.textContent = opts.undoLabel || "Undo";
      undoBtn.addEventListener("click", () => {
        dismiss();
        opts.onUndo();
      });
      toast.appendChild(undoBtn);
    }

    const closeBtn = document.createElement("button");
    closeBtn.type = "button";
    closeBtn.className = "roche-toast-close";
    closeBtn.setAttribute("aria-label", "Dismiss notification");
    closeBtn.textContent = "×";
    closeBtn.addEventListener("click", dismiss);
    toast.appendChild(closeBtn);

    region.replaceChildren(toast);
    const startTimer = () => {
      dismissTimer = window.setTimeout(dismiss, opts.durationMs || 7000);
    };
    const pauseTimer = () => {
      if (dismissTimer) {
        window.clearTimeout(dismissTimer);
        dismissTimer = null;
      }
    };
    toast.addEventListener("mouseenter", pauseTimer);
    toast.addEventListener("focusin", pauseTimer);
    toast.addEventListener("mouseleave", startTimer);
    toast.addEventListener("focusout", startTimer);
    startTimer();
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
    const errEl = document.getElementById("roche-player-err");
    const backBtn = document.getElementById("roche-player-back");
    const fwdBtn = document.getElementById("roche-player-fwd");
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

    const setPlayerError = (msg) => {
      if (!errEl) return;
      if (msg) {
        errEl.textContent = msg;
        errEl.hidden = false;
      } else {
        errEl.textContent = "";
        errEl.hidden = true;
      }
    };

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
      postProgress(activeItem.itemId, frac >= 0.98 ? 1.0 : frac, keepalive, "audio");
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
        btn.classList.toggle("is-playing", isCurrent);
        btn.setAttribute("aria-pressed", isCurrent ? "true" : "false");
        btn.setAttribute("aria-label", `${isCurrent ? "Pause" : "Play"} ${title}`);
        const glyph = btn.querySelector(".js-play-glyph");
        if (glyph) glyph.textContent = isCurrent ? "⏸" : "▶";
        const label = btn.querySelector(".js-play-label");
        if (label) label.textContent = isCurrent ? "Pause" : "Play";
      }
    };
    syncPlayButtonsFn = syncPlayButtons;
    isAudioActiveForItemFn = (id) => Boolean(activeItem?.itemId && id && activeItem.itemId === id);

    const safePlay = () => {
      const p = audio.play?.();
      if (p && typeof p.catch === "function") {
        p.catch((err) => {
          if (err?.name === "AbortError") return;
          setPlayerError("Playback could not start. Press Play on the player bar to retry.");
          syncPlayButtons();
        });
      }
    };

    const loadTrack = (meta, autoplay) => {
      clearPendingSeekListener();
      setPlayerError("");
      if (activeItem?.itemId && activeItem.itemId !== meta.itemId) {
        flushActiveProgress(true);
      }
      activeItem = meta;
      completionSent = false;
      bar.hidden = false;
      document.body?.classList.add("has-player-bar");
      document.documentElement?.classList.add("has-player-bar");
      if (titleEl) titleEl.textContent = meta.title || "Audio";
      if (subEl) subEl.textContent = meta.feed || "Audio";
      if (readerLink) {
        readerLink.href = `/reader/${encodeURIComponent(meta.itemId)}`;
        readerLink.setAttribute("aria-label", `Open ${meta.title || "item"}`);
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
        safePlay();
      }
      saveSession();
    };

    audio.addEventListener("timeupdate", () => {
      if (!activeItem || !Number.isFinite(audio.duration) || audio.duration <= 0) return;
      const frac = audio.currentTime / audio.duration;
      const now = Date.now();
      if (frac >= 0.98 && !completionSent) {
        completionSent = true;
        postProgress(activeItem.itemId, 1.0, true, "audio");
        markReaderUiFinished(activeItem.itemId, `Finished "${activeItem.title || "episode"}"`);
        saveSession();
      } else if (now - lastSentAt >= 5000) {
        lastSentAt = now;
        postProgress(activeItem.itemId, frac, false, "audio");
        saveSession();
      }
    });

    audio.addEventListener("play", () => {
      setPlayerError("");
      syncPlayButtons();
    });

    audio.addEventListener("playing", () => {
      setPlayerError("");
    });

    audio.addEventListener("error", () => {
      if (!activeItem) return;
      setPlayerError("Audio stream failed to load.");
      syncPlayButtons();
    });

    audio.addEventListener("pause", () => {
      flushActiveProgress(true);
      saveSession();
      syncPlayButtons();
    });

    audio.addEventListener("ended", () => {
      if (!activeItem) return;
      if (!completionSent) {
        completionSent = true;
        postProgress(activeItem.itemId, 1.0, true, "audio");
        markReaderUiFinished(activeItem.itemId, `Finished "${activeItem.title || "episode"}"`);
      }
      saveSession();
      syncPlayButtons();
    });

    window.addEventListener("pagehide", () => {
      flushActiveProgress(true);
      saveSession();
    });

    const seekBy = (deltaSecs) => {
      if (!activeItem) return;
      const dur = Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : Infinity;
      const next = Math.max(0, Math.min(dur, (audio.currentTime || 0) + deltaSecs));
      try {
        audio.currentTime = next;
      } catch {}
      saveSession();
      flushActiveProgress(false);
    };

    backBtn?.addEventListener("click", () => seekBy(-15));
    fwdBtn?.addEventListener("click", () => seekBy(30));

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
      setPlayerError("");
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
          safePlay();
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

    const hasLoadedAudio = () => Boolean(activeItem && !bar.hidden && audio.getAttribute("src"));

    const applyPjaxHtml = (html, finalUrl, fallbackUrl, pushHistory, targetHash) => {
      const doc = new DOMParser().parseFromString(html, "text/html");
      const nextMain = doc.getElementById("roche-main");
      const curMain = document.getElementById("roche-main");
      if (!nextMain || !curMain) {
        if (!hasLoadedAudio() && fallbackUrl) {
          window.location.href = fallbackUrl;
        } else {
          showInlinePjaxError("Received an unexpected page response.");
        }
        return false;
      }
      for (const dlg of document.querySelectorAll("dialog[open]")) {
        try {
          dlg.close();
        } catch {}
      }
      syncCsrfFromDoc(doc);
      if (!nextMain.querySelector(".js-reader-root")) {
        document.getElementById("roche-read-progress")?.remove();
      }
      curMain.replaceChildren(...Array.from(nextMain.childNodes));
      const nextNav = doc.getElementById("roche-nav");
      const curNav = document.getElementById("roche-nav");
      if (nextNav && curNav) {
        curNav.replaceChildren(...Array.from(nextNav.childNodes));
      }
      const nextSearchInput = doc.getElementById("roche-global-search");
      const curSearchInput = document.getElementById("roche-global-search");
      if (nextSearchInput && curSearchInput) {
        curSearchInput.value = nextSearchInput.value || "";
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
      const curMain = document.getElementById("roche-main");
      curMain?.setAttribute("aria-busy", "true");

      try {
        const resp = await fetch(reqUrl, opts);
        if (controller && activePjaxController !== controller) return;
        if (resp.redirected && isLoginUrl(resp.url)) {
          window.location.href = resp.url;
          return;
        }
        if (resp.status === 401) {
          const curDest = `${window.location.pathname}${window.location.search}`;
          const loginHref =
            curDest && curDest !== "/" && !curDest.startsWith("/login")
              ? `/login?return_to=${encodeURIComponent(curDest)}`
              : "/login";
          if (!hasLoadedAudio()) {
            window.location.href = loginHref;
          } else {
            showInlinePjaxError("Your session has expired. Audio playback continues —", {
              href: loginHref,
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
          if (!isPost && !hasLoadedAudio() && fallbackUrl) {
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
        if (!isPost && !hasLoadedAudio() && fallbackUrl) {
          window.location.href = fallbackUrl;
        } else {
          showInlinePjaxError("Network error while loading page. Audio playback continues.");
        }
      } finally {
        if (controller && activePjaxController === controller) {
          activePjaxController = null;
          document.getElementById("roche-main")?.removeAttribute("aria-busy");
        }
      }
    };

    navigateViaPjaxFn = (href) => {
      if (!href) return;
      if (!hasLoadedAudio()) {
        window.location.href = href;
        return;
      }
      try {
        const url = new URL(href, window.location.origin);
        if (url.origin !== window.location.origin) {
          window.location.href = href;
          return;
        }
        void runPjaxFetch(
          `${url.pathname}${url.search}`,
          { credentials: "same-origin" },
          href,
          true,
          url.hash,
          false
        );
      } catch {
        window.location.href = href;
      }
    };

    /* Seamless PJAX navigation & form submission while player bar holds an episode */
    document.addEventListener("click", (ev) => {
      if (!hasLoadedAudio()) return;
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
      if (!hasLoadedAudio() || ev.defaultPrevented) return;
      const form = ev.target;
      if (!form || form.tagName !== "FORM" || form.target) return;
      if (form.classList.contains("js-triage-form")) return;
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
      if (!pjaxNavigated && !hasLoadedAudio()) return;
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

  /* --- 2. Reader Scroll & Video Progress --- */
  let readerCleanup = null;
  const initReaderProgress = () => {
    if (readerCleanup) {
      readerCleanup();
      readerCleanup = null;
    }
    const root = document.querySelector(".js-reader-root");
    if (!root) return;

    const isBoardReader = root.getAttribute("data-board-reader") === "1";
    const hasReadableBody = root.getAttribute("data-has-readable-body") === "1";
    const promoteFinishAction = root.getAttribute("data-promote-finish-action") || "";
    const itemId = root.getAttribute("data-item-id") || "";
    const kind = root.getAttribute("data-kind") || "article";
    const cleanups = [];

    const video = root.querySelector("video.js-reader-video");
    if (video && itemId) {
      const savedProg = Number.parseFloat(video.getAttribute("data-progress") || "0") || 0;
      let lastSentAt = 0;
      let doneSent = false;
      const applyVideoSeek = () => {
        if (savedProg > 0.02 && savedProg < 0.98 && Number.isFinite(video.duration) && video.duration > 0) {
          try {
            video.currentTime = savedProg * video.duration;
          } catch {}
        }
      };
      if (video.readyState >= 1) {
        applyVideoSeek();
      } else {
        video.addEventListener("loadedmetadata", applyVideoSeek, { once: true });
      }
      const onTime = () => {
        if (!Number.isFinite(video.duration) || video.duration <= 0) return;
        const frac = video.currentTime / video.duration;
        const now = Date.now();
        if (frac >= 0.96 && !doneSent) {
          doneSent = true;
          postProgress(itemId, 1.0, true);
        } else if (now - lastSentAt >= 5000) {
          lastSentAt = now;
          postProgress(itemId, frac, false);
        }
      };
      const onPause = () => {
        if (!Number.isFinite(video.duration) || video.duration <= 0) return;
        postProgress(itemId, video.currentTime / video.duration, true);
      };
      const onEnded = () => {
        doneSent = true;
        postProgress(itemId, 1.0, true);
      };
      video.addEventListener("timeupdate", onTime);
      video.addEventListener("pause", onPause);
      video.addEventListener("ended", onEnded);
      cleanups.push(() => {
        video.removeEventListener("timeupdate", onTime);
        video.removeEventListener("pause", onPause);
        video.removeEventListener("ended", onEnded);
      });
    }

    const contentType = root.getAttribute("data-content-type") || "";
    const canTrackScroll = isBoardReader
      ? hasReadableBody && Boolean(promoteFinishAction)
      : (kind === "article" || kind === "paper") && contentType !== "audio" && Boolean(itemId);

    if (canTrackScroll) {
      let best = Number.parseFloat(root.getAttribute("data-progress") || "0") || 0;
      let lastPosted = best;
      let timer = null;
      let dwellTimer = null;
      let autoFinishSent = root.getAttribute("data-finished") === "1";

      const isSuppressedOrDone = () =>
        autoFinishSent ||
        root.getAttribute("data-finished") === "1" ||
        root.getAttribute("data-dismissed") === "1" ||
        root.getAttribute("data-suppress-auto-finish") === "1";

      const maxScrollablePixels = () =>
        Math.max(0, document.documentElement.scrollHeight - window.innerHeight);

      const measureFraction = () => {
        const maxScroll = maxScrollablePixels();
        if (maxScroll <= 8) return 1.0;
        return Math.max(0, Math.min(1, window.scrollY / maxScroll));
      };

      const cancelDwellTimer = () => {
        if (dwellTimer) {
          window.clearTimeout(dwellTimer);
          dwellTimer = null;
        }
      };

      const triggerAutoFinish = () => {
        if (isSuppressedOrDone() || (itemId && isAudioActiveForItemFn?.(itemId))) return;
        autoFinishSent = true;
        cancelDwellTimer();
        lastPosted = 1.0;
        if (isBoardReader) {
          const csrf = getCsrfToken();
          const params = new URLSearchParams();
          params.append("_csrf", csrf);
          params.append("action", "finish");
          fetch(promoteFinishAction, {
            method: "POST",
            headers: {
              "Content-Type": "application/x-www-form-urlencoded",
              "X-Roche-Csrf": csrf,
              "X-Roche-Ajax": "1",
              Accept: "application/json",
            },
            body: params.toString(),
            credentials: "same-origin",
          })
            .then((resp) => (resp.ok && !isLoginUrl(resp.url) ? resp.json() : null))
            .then((data) => {
              if (!data?.entry_id) {
                autoFinishSent = false;
                return;
              }
              root.setAttribute("data-item-id", data.entry_id);
              const finishForm = root.querySelector(".js-finish-toggle-form");
              if (finishForm && data.unfinish_action) {
                finishForm.setAttribute("data-unfinish-action", data.unfinish_action);
              }
              markReaderUiFinished(data.entry_id, "Marked finished & logged to Diary", {
                isBoardReader: true,
                rankHref: data.rank_href || null,
                entryHref: data.entry_href || null,
                queueAction: data.queue_action || null,
              });
            })
            .catch(() => {
              autoFinishSent = false;
            });
          return;
        }
        postProgress(itemId, 1.0, false, "reader");
        markReaderUiFinished(itemId, "Marked finished");
      };

      const scheduleShortArticleDwell = () => {
        cancelDwellTimer();
        if (document.hidden || isSuppressedOrDone() || maxScrollablePixels() > 8) return;
        dwellTimer = window.setTimeout(() => {
          dwellTimer = null;
          if (!document.hidden && maxScrollablePixels() <= 8) {
            triggerAutoFinish();
          }
        }, 4000);
      };

      const checkProgress = () => {
        if (itemId && isAudioActiveForItemFn?.(itemId)) return;
        if (maxScrollablePixels() <= 8) return;
        const frac = measureFraction();
        if (frac > best) best = frac;
        if (best >= 0.98) {
          triggerAutoFinish();
          return;
        }
        if (!isBoardReader && !isSuppressedOrDone() && best - lastPosted >= 0.05) {
          lastPosted = best;
          postProgress(itemId, best, false, "reader");
        }
      };

      const onScroll = () => {
        if (timer) return;
        timer = window.setTimeout(() => {
          timer = null;
          checkProgress();
        }, 350);
      };

      const onVisibilityChange = () => {
        if (document.hidden) {
          cancelDwellTimer();
        } else {
          scheduleShortArticleDwell();
        }
      };

      window.addEventListener("scroll", onScroll, { passive: true });
      document.addEventListener("visibilitychange", onVisibilityChange);
      scheduleShortArticleDwell();

      const layoutScope = root.closest(".reader-layout") || document;
      const dismissForms = Array.from(layoutScope.querySelectorAll(".js-reader-dismiss-form"));
      const onDismissSubmit = () => {
        autoFinishSent = true;
        cancelDwellTimer();
      };
      for (const formEl of dismissForms) {
        formEl.addEventListener("submit", onDismissSubmit);
      }

      const resumeBtn = root.querySelector(".js-reader-resume-btn");
      const onResumeClick = () => {
        const targetFrac = Number.parseFloat(resumeBtn?.getAttribute("data-resume-progress") || "0");
        if (!Number.isFinite(targetFrac) || targetFrac <= 0) return;
        const targetY = Math.round(targetFrac * maxScrollablePixels());
        const reducedMotion = Boolean(window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches);
        window.scrollTo({ top: targetY, behavior: reducedMotion ? "auto" : "smooth" });
        if (resumeBtn) resumeBtn.hidden = true;
      };
      resumeBtn?.addEventListener("click", onResumeClick);

      cleanups.push(() => {
        window.removeEventListener("scroll", onScroll);
        document.removeEventListener("visibilitychange", onVisibilityChange);
        for (const formEl of dismissForms) {
          formEl.removeEventListener("submit", onDismissSubmit);
        }
        resumeBtn?.removeEventListener("click", onResumeClick);
        if (timer) window.clearTimeout(timer);
        cancelDwellTimer();
      });
    }

    if (cleanups.length > 0) {
      readerCleanup = () => {
        for (const fn of cleanups) fn();
      };
    }
  };

  /* --- 3. Interactive k-Way Ranking Board (Side-by-Side Tie Buckets + Drag & Drop) --- */
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
    const kInput = form.querySelector("input[name='k']");
    const roundExcludedInput = form.querySelector("input[name='round_excluded']");
    const tieAllBtn = form.querySelector(".js-rank-tie-all");
    const excludedTray = form.querySelector(".js-rank-excluded-tray");
    const excludedLabel = form.querySelector(".js-rank-excluded-label");
    const excludedChips = form.querySelector(".js-rank-excluded-chips");
    const restoreAllBtn = form.querySelector(".js-rank-restore-all");
    const MIN_K = 2;
    const MAX_K_DEFAULT = 5;
    const MAX_COVERED_CLUSTERS = 12;
    const MAX_EXCLUDED_TOKENS = 24;

    const sizeGroup = document.querySelector(".js-rank-size-group");
    const sizeHint = document.querySelector(".js-rank-size-hint");
    const kbdMaxEl = document.querySelector(".js-rank-kbd-max");
    const skipForm = document.querySelector(".js-rank-skip-form");
    const skipKInput = skipForm?.querySelector("input[name='k']");
    const skipCoveredInput = skipForm?.querySelector("input[name='covered']");
    const skipExcludedInput = skipForm?.querySelector("input[name='excluded']");
    if (!list || !hiddenInput) return;

    const initialCards = Array.from(list.querySelectorAll(".js-rank-card"));
    if (initialCards.length === 0) return;

    const allLoadedCount = initialCards.length;
    const loadedMaxK = Math.max(
      MIN_K,
      Math.min(
        MAX_K_DEFAULT,
        Number.parseInt(list.getAttribute("data-loaded-max-k") || String(allLoadedCount), 10) ||
          allLoadedCount
      )
    );
    initialCards.forEach((card, idx) => {
      if (!card.dataset.initIdx) card.dataset.initIdx = String(idx);
    });

    let buckets = [];
    for (const [idx, card] of initialCards.entries()) {
      if (idx > 0 && card.getAttribute("data-tied") === "1" && buckets.length > 0) {
        buckets[buckets.length - 1].push(card);
      } else {
        buckets.push([card]);
      }
    }
    let excludedEntries = [];
    const maxPillK = sizeGroup
      ? Array.from(sizeGroup.querySelectorAll(".js-rank-size-opt")).reduce(
          (acc, el) =>
            Math.max(acc, Number.parseInt(el.getAttribute("data-rank-k") || "0", 10)),
          MIN_K
        )
      : Math.max(MIN_K, allLoadedCount);
    let currentK = Math.max(
      MIN_K,
      Math.min(
        maxPillK,
        Number.parseInt(kInput?.value || String(allLoadedCount), 10) || allLoadedCount
      )
    );
    let selectedCard = initialCards[0];
    let draggedCard = null;
    let dropTarget = null;

    const flatCards = () => buckets.flat();
    const cardIsFocus = (card) => card?.getAttribute("data-focus") === "1";
    const cardPriority = (card) =>
      Number.parseInt(card?.getAttribute("data-priority") || "99", 10);
    const cardInitIdx = (card) =>
      Number.parseInt(card?.dataset?.initIdx || "0", 10);
    const canExcludeCard = (card, totalActive = flatCards().length) =>
      Boolean(card && !cardIsFocus(card) && totalActive > MIN_K);
    const shortToken = (id) => (id || "").slice(0, 8);

    const buildActiveSkipCovered = (activeIds) => {
      const prevRaw = (skipForm?.dataset?.prevCovered || "").trim();
      const parts = prevRaw ? prevRaw.split("|").filter(Boolean) : [];
      const activeTokens = Array.from(
        new Set(activeIds.map(shortToken).filter(Boolean))
      ).sort();
      const sig = activeTokens.join(".");
      if (sig && !parts.includes(sig)) {
        parts.push(sig);
      }
      if (parts.length > MAX_COVERED_CLUSTERS) {
        parts.splice(0, parts.length - MAX_COVERED_CLUSTERS);
      }
      return parts.join("|");
    };

    const buildMergedExcluded = (prevRaw, manualIds, activeIds = []) => {
      const activeTokenSet = new Set(activeIds.map(shortToken).filter(Boolean));
      const listTokens = (prevRaw || "")
        .trim()
        .split(".")
        .filter((t) => t && !activeTokenSet.has(t));
      for (const id of manualIds) {
        const tok = shortToken(id);
        if (tok && !activeTokenSet.has(tok) && !listTokens.includes(tok)) {
          listTokens.push(tok);
        }
      }
      if (listTokens.length > MAX_EXCLUDED_TOKENS) {
        listTokens.splice(0, listTokens.length - MAX_EXCLUDED_TOKENS);
      }
      return listTokens.join(".");
    };

    const buildReloadHrefWithExclusions = (fallbackHref) => {
      const manualIds = excludedEntries
        .filter((e) => e.manual)
        .map((e) => e.card.getAttribute("data-entry-id") || "")
        .filter(Boolean);
      if (manualIds.length === 0) return fallbackHref;
      try {
        const url = new URL(fallbackHref, window.location.origin);
        const merged = buildMergedExcluded(url.searchParams.get("excluded") || "", manualIds);
        if (merged) url.searchParams.set("excluded", merged);
        return url.pathname + url.search + url.hash;
      } catch {
        return fallbackHref;
      }
    };

    const persistRankKCookie = (k) => {
      try {
        const securePart = window.location.protocol === "https:" ? "; Secure" : "";
        document.cookie = `roche_rank_k=${k}; Path=/; Max-Age=31536000; SameSite=Lax${securePart}`;
      } catch {}
    };

    const syncSizeControls = (targetK) => {
      currentK = Math.max(MIN_K, Math.min(maxPillK, targetK));
      if (kInput) kInput.value = String(currentK);
      if (skipKInput) skipKInput.value = String(currentK);
      if (sizeGroup) {
        for (const opt of sizeGroup.querySelectorAll(".js-rank-size-opt")) {
          const optK = Number.parseInt(opt.getAttribute("data-rank-k") || "0", 10);
          const isCur = optK === currentK;
          opt.classList.toggle("is-active", isCur);
          if (isCur) opt.setAttribute("aria-current", "true");
          else opt.removeAttribute("aria-current");
        }
      }
    };

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

    const clearDropHints = () => {
      dropTarget = null;
      for (const el of list.querySelectorAll(".rank-bucket")) {
        el.classList.remove("is-drop-before", "is-drop-after", "is-drop-tie");
      }
    };

    const syncSelection = (announcement = "") => {
      for (const card of flatCards()) {
        const isSelected = card === selectedCard;
        card.classList.toggle("is-selected", isSelected);
        if (isSelected) {
          card.setAttribute("aria-current", "true");
        } else {
          card.removeAttribute("aria-current");
        }
      }
      if (announcement) announce(announcement);
    };

    const syncBoard = (announcement = "") => {
      const previouslyFocused = document.activeElement;
      const shouldRestoreFocus = Boolean(previouslyFocused && list.contains(previouslyFocused));
      const groups = [];
      let flatIdx = 0;
      const frag = document.createDocumentFragment();

      buckets = buckets.filter((b) => b.length > 0);
      const totalActive = buckets.reduce((sum, b) => sum + b.length, 0);
      if (!selectedCard || !buckets.some((b) => b.includes(selectedCard))) {
        selectedCard = buckets[0]?.[0] || null;
      }

      for (const [bIdx, bucket] of buckets.entries()) {
        const displayRank = flatIdx + 1;
        const isTiedBucket = bucket.length > 1;
        const ids = [];

        const bucketEl = document.createElement("li");
        bucketEl.className = `rank-bucket${isTiedBucket ? " is-tied-bucket" : ""}`;
        bucketEl.dataset.bucketIndex = String(bIdx);

        const gutter = document.createElement("div");
        gutter.className = "rank-bucket-gutter";
        gutter.setAttribute("aria-hidden", "true");

        const posEl = document.createElement("span");
        posEl.className = "rank-pos js-rank-pos";
        posEl.textContent = `#${displayRank}`;

        const tieTag = document.createElement("span");
        tieTag.className = "rank-tie-tag";
        tieTag.textContent = "tie";

        gutter.append(posEl, tieTag);

        const cardsWrap = document.createElement("div");
        cardsWrap.className = "rank-bucket-cards";

        for (const [idxInBucket, card] of bucket.entries()) {
          const tiedAfterFirst = idxInBucket > 0;
          const isSelected = card === selectedCard;
          card.setAttribute("data-tied", tiedAfterFirst ? "1" : "0");
          card.classList.toggle("is-tied", isTiedBucket);
          card.classList.toggle("is-selected", isSelected);
          if (isSelected) {
            card.setAttribute("aria-current", "true");
          } else {
            card.removeAttribute("aria-current");
          }
          const upBtn = card.querySelector(".js-card-up");
          if (upBtn) upBtn.disabled = !isTiedBucket && bIdx === 0;
          const downBtn = card.querySelector(".js-card-down");
          if (downBtn) downBtn.disabled = !isTiedBucket && bIdx === buckets.length - 1;
          const tieBtn = card.querySelector(".js-card-tie");
          if (tieBtn) {
            const title = cardTitle(card);
            tieBtn.disabled = !isTiedBucket && bIdx === 0;
            tieBtn.replaceChildren();
            if (isTiedBucket) {
              tieBtn.append(document.createTextNode("Untie"));
              tieBtn.setAttribute("aria-label", `Untie ${title}`);
              tieBtn.setAttribute("aria-pressed", "true");
              tieBtn.title = "Split into its own rank (=)";
            } else {
              const eqSpan = document.createElement("span");
              eqSpan.setAttribute("aria-hidden", "true");
              eqSpan.textContent = "=";
              tieBtn.append(eqSpan, document.createTextNode(" Tie"));
              tieBtn.setAttribute("aria-label", `Tie ${title} with above`);
              tieBtn.setAttribute("aria-pressed", "false");
              tieBtn.title = "Tie with rank above (=)";
            }
          }
          const excludeBtn = card.querySelector(".js-card-exclude");
          if (excludeBtn) {
            excludeBtn.disabled = !canExcludeCard(card, totalActive);
          }
          cardsWrap.appendChild(card);
          ids.push(card.getAttribute("data-entry-id"));
          flatIdx += 1;
        }

        bucketEl.append(gutter, cardsWrap);
        frag.appendChild(bucketEl);
        groups.push(ids);
      }

      list.replaceChildren(frag);

      const manualExcludedIds = excludedEntries
        .filter((e) => e.manual)
        .map((e) => e.card.getAttribute("data-entry-id") || "")
        .filter(Boolean);
      const manualCount = manualExcludedIds.length;
      const trimmedCount = excludedEntries.length - manualCount;

      if (excludedTray && excludedChips) {
        const exCount = excludedEntries.length;
        excludedTray.hidden = exCount === 0;
        if (excludedLabel) {
          if (manualCount === 0) {
            excludedLabel.textContent = `Set aside at this size (${exCount})`;
          } else if (trimmedCount === 0) {
            excludedLabel.textContent = `Excluded from this comparison (${exCount})`;
          } else {
            excludedLabel.textContent = `Left out of this comparison (${exCount})`;
          }
        }
        if (restoreAllBtn) {
          restoreAllBtn.hidden = exCount <= 1;
        }
        const chipFrag = document.createDocumentFragment();
        for (const entry of excludedEntries) {
          const title = cardTitle(entry.card);
          const chip = document.createElement("button");
          chip.type = "button";
          chip.className = `rank-excluded-chip js-rank-restore-chip${
            entry.manual ? " is-manual-excluded" : ""
          }`;
          chip.setAttribute("data-restore-id", entry.card.getAttribute("data-entry-id") || "");
          chip.setAttribute("aria-label", `Restore ${title}`);
          chip.title = entry.manual
            ? "Manually excluded (click to restore)"
            : "Set aside by cluster size (click to restore)";
          const plus = document.createElement("span");
          plus.className = "rank-excluded-chip-plus";
          plus.setAttribute("aria-hidden", "true");
          plus.textContent = "+";
          chip.append(plus, document.createTextNode(title));
          chipFrag.appendChild(chip);
        }
        excludedChips.replaceChildren(chipFrag);
      }

      if (sizeHint) {
        if (excludedEntries.length === 0) {
          sizeHint.textContent = `Comparing ${totalActive} items`;
        } else if (manualCount === 0) {
          sizeHint.textContent = `Comparing ${totalActive} of ${allLoadedCount} (${trimmedCount} set aside)`;
        } else if (trimmedCount === 0) {
          sizeHint.textContent = `Comparing ${totalActive} of ${allLoadedCount} (${manualCount} excluded)`;
        } else {
          sizeHint.textContent = `Comparing ${totalActive} of ${allLoadedCount} (${manualCount} excluded, ${trimmedCount} set aside)`;
        }
      }

      if (kbdMaxEl) {
        kbdMaxEl.textContent = String(totalActive);
      }

      const activeIds = groups.flat();
      if (roundExcludedInput) {
        roundExcludedInput.value = manualExcludedIds.join(",");
      }
      if (skipCoveredInput) {
        skipCoveredInput.value = buildActiveSkipCovered(activeIds);
      }
      if (skipExcludedInput) {
        skipExcludedInput.value = buildMergedExcluded(
          skipForm?.dataset?.prevExcluded || "",
          manualExcludedIds,
          activeIds
        );
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
          const fallbackBtn =
            (previouslyFocused.classList?.contains("js-card-up") &&
              selectedCard.querySelector(".js-card-down:not(:disabled)")) ||
            (previouslyFocused.classList?.contains("js-card-down") &&
              selectedCard.querySelector(".js-card-up:not(:disabled)")) ||
            selectedCard.querySelector(".js-card-up:not(:disabled), .js-card-down:not(:disabled)");
          (fallbackBtn || selectedCard).focus({ preventScroll: true });
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
      if (buckets[coords.b].length > 1) {
        buckets[coords.b].splice(coords.i, 1);
        buckets.splice(coords.b + 1, 0, [card]);
        msg = `Untied ${cardTitle(card)}`;
      } else if (coords.b > 0) {
        const [cur] = buckets.splice(coords.b, 1);
        buckets[coords.b - 1] = buckets[coords.b - 1].concat(cur);
        msg = `Tied ${cardTitle(card)} side-by-side with rank above`;
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

    const insertCardNaturally = (card) => {
      const targetIdx = cardInitIdx(card);
      let insertAt = buckets.length;
      for (let b = 0; b < buckets.length; b++) {
        const firstIdx = cardInitIdx(buckets[b][0]);
        if (targetIdx < firstIdx) {
          insertAt = b;
          break;
        }
      }
      buckets.splice(insertAt, 0, [card]);
    };

    const excludeCard = (card, manual = true) => {
      const all = flatCards();
      if (!canExcludeCard(card, all.length)) return false;
      const oldIdx = all.indexOf(card);
      const coords = findCoords(card);
      if (!coords) return false;
      buckets[coords.b].splice(coords.i, 1);
      if (buckets[coords.b].length === 0) {
        buckets.splice(coords.b, 1);
      }
      if (!excludedEntries.some((e) => e.card === card)) {
        excludedEntries.push({ card, manual });
      }
      if (selectedCard === card) {
        const rem = flatCards();
        selectedCard = rem[Math.min(oldIdx, rem.length - 1)] || rem[0] || null;
      }
      syncBoard(
        `Excluded ${cardTitle(card)} from this comparison. ${flatCards().length} items remaining.`
      );
      return true;
    };

    const syncExpandedSizeAfterRestore = () => {
      const activeCount = flatCards().length;
      if (activeCount > currentK) {
        const nextK = Math.min(maxPillK, activeCount);
        persistRankKCookie(nextK);
        syncSizeControls(nextK);
      }
    };

    const restoreCardById = (entryId) => {
      const idx = excludedEntries.findIndex(
        (e) => e.card.getAttribute("data-entry-id") === entryId
      );
      if (idx === -1) return;
      const [restored] = excludedEntries.splice(idx, 1);
      insertCardNaturally(restored.card);
      selectedCard = restored.card;
      syncExpandedSizeAfterRestore();
      syncBoard(`Restored ${cardTitle(restored.card)} to comparison.`);
      selectedCard?.focus({ preventScroll: true });
    };

    const restoreAllCards = () => {
      if (excludedEntries.length === 0) return;
      const toRestore = excludedEntries.splice(0, excludedEntries.length);
      toRestore.sort((a, b) => cardInitIdx(a.card) - cardInitIdx(b.card));
      for (const item of toRestore) {
        insertCardNaturally(item.card);
      }
      syncExpandedSizeAfterRestore();
      syncBoard(`Restored all ${toRestore.length} excluded items.`);
      selectedCard?.focus({ preventScroll: true });
    };

    const applyClusterSize = (targetK, fallbackHref) => {
      const clamped = Math.max(MIN_K, Math.min(maxPillK, targetK));
      persistRankKCookie(clamped);
      syncSizeControls(clamped);

      if (clamped > allLoadedCount && clamped > loadedMaxK && fallbackHref) {
        const destHref = buildReloadHrefWithExclusions(fallbackHref);
        if (navigateViaPjaxFn) navigateViaPjaxFn(destHref);
        else window.location.href = destHref;
        return;
      }

      const active = flatCards();
      if (clamped < active.length) {
        const removable = active
          .filter((c) => !cardIsFocus(c))
          .sort((a, b) => cardPriority(b) - cardPriority(a) || cardInitIdx(b) - cardInitIdx(a));
        const toDrop = removable.slice(0, active.length - clamped);
        for (const card of toDrop) {
          const coords = findCoords(card);
          if (!coords) continue;
          buckets[coords.b].splice(coords.i, 1);
          if (buckets[coords.b].length === 0) {
            buckets.splice(coords.b, 1);
          }
          if (!excludedEntries.some((e) => e.card === card)) {
            excludedEntries.push({ card, manual: false });
          }
        }
        const rem = flatCards();
        if (!rem.includes(selectedCard)) {
          selectedCard = rem[0] || null;
        }
        syncBoard(
          `Comparing ${rem.length} items. ${excludedEntries.length} set aside from this comparison.`
        );
      } else if (clamped > active.length) {
        const trimmed = excludedEntries
          .filter((e) => !e.manual)
          .sort((a, b) => cardPriority(a.card) - cardPriority(b.card));
        const toRestore = trimmed.slice(0, clamped - active.length);
        for (const entry of toRestore) {
          const idx = excludedEntries.indexOf(entry);
          if (idx !== -1) excludedEntries.splice(idx, 1);
          insertCardNaturally(entry.card);
        }
        syncBoard(`Comparing ${flatCards().length} items.`);
      } else {
        syncBoard(`Cluster size set to ${clamped}.`);
      }
    };

    const applyDrop = (card, targetBucketIdx, mode) => {
      const src = findCoords(card);
      if (!src) return;
      const srcWasSolo = buckets[src.b].length === 1;
      if (mode === "tie" && src.b === targetBucketIdx) return;
      if (srcWasSolo && src.b === targetBucketIdx) return;

      buckets[src.b].splice(src.i, 1);
      let adjustedTarget = targetBucketIdx;
      if (buckets[src.b].length === 0) {
        buckets.splice(src.b, 1);
        if (src.b < adjustedTarget) adjustedTarget -= 1;
      }
      adjustedTarget = Math.max(0, Math.min(buckets.length - 1, adjustedTarget));

      if (mode === "tie") {
        buckets[adjustedTarget].push(card);
        selectedCard = card;
        syncBoard(`Tied ${cardTitle(card)} side-by-side`);
      } else if (mode === "before") {
        buckets.splice(adjustedTarget, 0, [card]);
        selectedCard = card;
        syncBoard(`Moved ${cardTitle(card)} above`);
      } else {
        buckets.splice(adjustedTarget + 1, 0, [card]);
        selectedCard = card;
        syncBoard(`Moved ${cardTitle(card)} below`);
      }
    };

    let dragRects = [];
    const snapshotBucketRects = () => {
      dragRects = Array.from(list.querySelectorAll(".rank-bucket")).map((el) => {
        const rect = el.getBoundingClientRect();
        return {
          el,
          bucketIndex: Number.parseInt(el.dataset.bucketIndex || "0", 10),
          top: rect.top,
          bottom: rect.bottom,
          height: rect.height,
        };
      });
    };

    const updateDropHintAtY = (clientY) => {
      if (!draggedCard) return;
      if (dragRects.length === 0) snapshotBucketRects();
      const src = findCoords(draggedCard);
      if (!src) return;
      const srcSolo = buckets[src.b].length === 1;

      let hit = dragRects.find((r) => clientY >= r.top && clientY <= r.bottom);
      if (!hit && dragRects.length > 0) {
        hit = clientY < dragRects[0].top ? dragRects[0] : dragRects[dragRects.length - 1];
      }
      if (!hit) {
        clearDropHints();
        return;
      }

      const bIdx = hit.bucketIndex;
      const relY = hit.height > 0 ? (clientY - hit.top) / hit.height : 0.5;
      let mode = "tie";
      if (relY < 0.27) {
        mode = "before";
      } else if (relY > 0.73) {
        mode = "after";
      } else if (src.b === bIdx) {
        clearDropHints();
        return;
      }

      if (srcSolo && src.b === bIdx) {
        clearDropHints();
        return;
      }
      if (srcSolo && mode === "before" && bIdx === src.b + 1) {
        clearDropHints();
        return;
      }
      if (srcSolo && mode === "after" && bIdx === src.b - 1) {
        clearDropHints();
        return;
      }

      if (dropTarget && dropTarget.bucketIndex === bIdx && dropTarget.mode === mode) {
        return;
      }

      clearDropHints();
      dropTarget = { bucketIndex: bIdx, mode };
      hit.el.classList.add(
        mode === "before" ? "is-drop-before" : mode === "after" ? "is-drop-after" : "is-drop-tie"
      );
    };

    list.addEventListener("dragstart", (ev) => {
      if (ev.target?.closest?.("a, button")) {
        ev.preventDefault();
        return;
      }
      const card = ev.target?.closest?.(".js-rank-card");
      if (!card) return;
      draggedCard = card;
      selectedCard = card;
      snapshotBucketRects();
      card.classList.add("is-dragging");
      if (ev.dataTransfer) {
        ev.dataTransfer.effectAllowed = "move";
        ev.dataTransfer.setData("text/plain", card.getAttribute("data-entry-id") || "");
      }
    });

    list.addEventListener("dragover", (ev) => {
      if (!draggedCard) return;
      ev.preventDefault();
      if (ev.dataTransfer) ev.dataTransfer.dropEffect = "move";
      updateDropHintAtY(ev.clientY);
    });

    list.addEventListener("dragleave", (ev) => {
      if (!list.contains(ev.relatedTarget)) {
        clearDropHints();
      }
    });

    list.addEventListener("drop", (ev) => {
      if (!draggedCard) return;
      ev.preventDefault();
      const card = draggedCard;
      const target = dropTarget;
      card.classList.remove("is-dragging");
      draggedCard = null;
      dragRects = [];
      clearDropHints();
      if (target) {
        applyDrop(card, target.bucketIndex, target.mode);
      }
    });

    list.addEventListener("dragend", () => {
      if (draggedCard) draggedCard.classList.remove("is-dragging");
      draggedCard = null;
      dragRects = [];
      clearDropHints();
    });

    /* Touch / pen drag support via Pointer Events on grip handle */
    let activePointerId = null;
    list.addEventListener("pointerdown", (ev) => {
      if (ev.pointerType !== "touch" && ev.pointerType !== "pen") return;
      const grip = ev.target?.closest?.(".rank-grip");
      const card = grip?.closest?.(".js-rank-card");
      if (!card) return;
      ev.preventDefault();
      activePointerId = ev.pointerId;
      draggedCard = card;
      selectedCard = card;
      snapshotBucketRects();
      card.classList.add("is-dragging");
      try {
        grip.setPointerCapture?.(ev.pointerId);
      } catch {}
    });

    list.addEventListener("pointermove", (ev) => {
      if (activePointerId === null || ev.pointerId !== activePointerId || !draggedCard) return;
      ev.preventDefault();
      updateDropHintAtY(ev.clientY);
    });

    const finishPointerDrag = (ev) => {
      if (activePointerId === null || ev.pointerId !== activePointerId) return;
      activePointerId = null;
      const card = draggedCard;
      const target = dropTarget;
      if (card) card.classList.remove("is-dragging");
      draggedCard = null;
      dragRects = [];
      clearDropHints();
      if (card && target) {
        applyDrop(card, target.bucketIndex, target.mode);
      }
    };
    list.addEventListener("pointerup", finishPointerDrag);
    list.addEventListener("pointercancel", finishPointerDrag);

    list.addEventListener("focusin", (ev) => {
      const card = ev.target?.closest?.(".js-rank-card");
      if (card && card !== selectedCard) {
        selectedCard = card;
        syncSelection();
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
      } else if (ev.target.closest(".js-card-exclude")) {
        ev.preventDefault();
        excludeCard(card, true);
      } else {
        syncSelection();
      }
    });

    excludedChips?.addEventListener("click", (ev) => {
      const btn = ev.target?.closest?.(".js-rank-restore-chip");
      if (!btn) return;
      ev.preventDefault();
      restoreCardById(btn.getAttribute("data-restore-id") || "");
    });

    restoreAllBtn?.addEventListener("click", (ev) => {
      ev.preventDefault();
      restoreAllCards();
    });

    sizeGroup?.addEventListener("click", (ev) => {
      const opt = ev.target?.closest?.(".js-rank-size-opt");
      if (!opt) return;
      if (ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) return;
      ev.preventDefault();
      const targetK = Number.parseInt(opt.getAttribute("data-rank-k") || "5", 10);
      applyClusterSize(targetK, opt.href);
    });

    tieAllBtn?.addEventListener("click", (ev) => {
      ev.preventDefault();
      tieAll();
    });

    const onKeyDown = (ev) => {
      if (!document.body.contains(form)) return;
      if (document.querySelector("dialog[open]")) return;
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
        syncSelection(`Selected ${cardTitle(selectedCard)}`);
        selectedCard.focus({ preventScroll: true });
      } else if (ev.key === "k" || (ev.key === "ArrowUp" && !ev.shiftKey)) {
        ev.preventDefault();
        selectedCard = cards[Math.max(0, curIdx - 1)];
        syncSelection(`Selected ${cardTitle(selectedCard)}`);
        selectedCard.focus({ preventScroll: true });
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
            syncSelection();
          }
          selectedCard.focus({ preventScroll: true });
        }
      } else if (ev.key === "=") {
        ev.preventDefault();
        toggleTieWithAbove(selectedCard);
      } else if (ev.key === "t") {
        ev.preventDefault();
        tieAll();
      } else if (ev.key === "x" || ev.key === "Backspace" || ev.key === "Delete") {
        ev.preventDefault();
        if (canExcludeCard(selectedCard)) {
          excludeCard(selectedCard, true);
          selectedCard?.focus({ preventScroll: true });
        } else {
          announce(
            cardIsFocus(selectedCard)
              ? "Cannot exclude the item being placed."
              : `At least ${MIN_K} items are required for a comparison.`
          );
        }
      } else if (ev.key === "-" && sizeGroup) {
        const targetK = Math.max(MIN_K, currentK - 1);
        const opt = sizeGroup.querySelector(`.js-rank-size-opt[data-rank-k="${targetK}"]`);
        if (opt && targetK !== currentK) {
          ev.preventDefault();
          applyClusterSize(targetK, opt.href);
        }
      } else if (ev.key === "+" && sizeGroup) {
        const targetK = Math.min(maxPillK, currentK + 1);
        const opt = sizeGroup.querySelector(`.js-rank-size-opt[data-rank-k="${targetK}"]`);
        if (opt && targetK !== currentK) {
          ev.preventDefault();
          applyClusterSize(targetK, opt.href);
        }
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
    const activeEl = document.activeElement;
    if (
      selectedCard &&
      (!activeEl || activeEl === document.body || activeEl.id === "roche-main")
    ) {
      selectedCard.focus({ preventScroll: true });
    }
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
  let refreshHideTimer = null;

  const initRefreshBanner = () => {
    const banner = document.getElementById("roche-refresh-banner");
    if (!banner) return;
    const label = banner.querySelector(".js-refresh-label");
    let baselineUnread = null;
    let consecutiveErrors = 0;
    const MAX_POLL_ERRORS = 4;

    const isBoardRefreshScope = () =>
      banner.dataset.refreshScope === "board" ||
      (window.location.pathname.startsWith("/boards/") && !window.location.pathname.includes("/read/"));

    const showCompletionCue = (delta, needsManualReload) => {
      if (refreshHideTimer) clearTimeout(refreshHideTimer);
      banner.classList.add("is-complete");
      banner.hidden = false;
      banner.setAttribute("data-active", "0");
      const boardScope = isBoardRefreshScope();
      if (label) {
        label.replaceChildren();
        if (delta > 0) {
          const itemWord = delta === 1 ? "item" : "items";
          label.appendChild(document.createTextNode(`+${delta} new ${itemWord}`));
          if (needsManualReload) {
            label.appendChild(document.createTextNode(" — "));
            const reloadLink = document.createElement("a");
            reloadLink.href = `${window.location.pathname}${window.location.search}`;
            reloadLink.className = "refresh-reload-link";
            reloadLink.textContent = "Reload";
            label.appendChild(reloadLink);
          }
        } else {
          label.textContent = boardScope ? "Board updated" : "Up to date";
        }
      }
      if (!needsManualReload || delta === 0) {
        refreshHideTimer = setTimeout(() => {
          banner.hidden = true;
          banner.classList.remove("is-complete");
          delete banner.dataset.refreshScope;
          if (label) label.textContent = "Refreshing feeds & metadata in background…";
        }, 3500);
      }
    };

    const cleanCurrentPathSearch = () => `${window.location.pathname}${window.location.search}`;

    const scheduleCheck = (delayMs) => {
      if (refreshPollTimer) clearTimeout(refreshPollTimer);
      refreshPollTimer = setTimeout(async () => {
        refreshPollTimer = null;
        try {
          const resp = await fetch("/web/refresh-status", {
            headers: { Accept: "application/json" },
            credentials: "same-origin",
          });
          if (resp.redirected && isLoginUrl(resp.url)) return;
          if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
          const data = await resp.json();
          consecutiveErrors = 0;
          if (baselineUnread === null && Number.isFinite(data?.unread_items_count)) {
            baselineUnread = data.unread_items_count;
          }
          if (data?.is_refreshing) {
            if (refreshHideTimer) {
              clearTimeout(refreshHideTimer);
              refreshHideTimer = null;
            }
            banner.classList.remove("is-complete");
            banner.hidden = false;
            banner.setAttribute("data-active", "1");
            if (label && Number.isFinite(data.active_refresh_count) && data.active_refresh_count > 1) {
              label.textContent = `Refreshing ${data.active_refresh_count} sources in background…`;
            } else if (label) {
              label.textContent = isBoardRefreshScope()
                ? "Refreshing board…"
                : "Refreshing feeds & metadata in background…";
            }
            scheduleCheck(1500);
          } else {
            const wasActive = banner.getAttribute("data-active") === "1";
            const currentUnread = Number.isFinite(data?.unread_items_count)
              ? data.unread_items_count
              : (baselineUnread ?? 0);
            const delta = baselineUnread !== null ? Math.max(0, currentUnread - baselineUnread) : 0;
            if (!wasActive) {
              banner.setAttribute("data-active", "0");
              return;
            }
            banner.setAttribute("data-active", "0");
            const curPath = window.location.pathname;
            const isBoardListPath =
              curPath === "/boards" ||
              (curPath.startsWith("/boards/") && !curPath.includes("/read/"));
            const canAutoSwap =
              !hasDirtyFormOrOpenDetails() &&
              (["/", "/inbox", "/feeds", "/queue"].includes(curPath) || isBoardListPath);
            if (canAutoSwap) {
              const curUrl = cleanCurrentPathSearch();
              const r = await fetch(curUrl, { credentials: "same-origin" });
              if (r.redirected && isLoginUrl(r.url)) {
                window.location.href = r.url;
                return;
              }
              if (r.ok) {
                const html = await r.text();
                if (cleanCurrentPathSearch() !== curUrl) {
                  banner.hidden = true;
                  return;
                }
                if (hasDirtyFormOrOpenDetails()) {
                  showCompletionCue(delta, true);
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
                showCompletionCue(delta, false);
                return;
              }
            }
            showCompletionCue(delta, true);
          }
        } catch {
          consecutiveErrors += 1;
          if (consecutiveErrors <= MAX_POLL_ERRORS) {
            const backoffMs = Math.min(12000, 1500 * Math.pow(2, consecutiveErrors - 1));
            scheduleCheck(backoffMs);
          } else {
            banner.hidden = true;
            banner.setAttribute("data-active", "0");
          }
        }
      }, delayMs);
    };

    if (banner.getAttribute("data-active") === "1") {
      scheduleCheck(1200);
    }
  };

  document.addEventListener("submit", (ev) => {
    if (ev.defaultPrevented) return;
    const form = ev.target;
    const action = form?.getAttribute?.("action") || "";
    const isSingleBoardRefresh =
      action === "/web/boards/create" ||
      (action.startsWith("/web/boards/") &&
        action.endsWith("/refresh") &&
        action !== "/web/boards/refresh-all");
    if (
      action.includes("/refresh") ||
      action === "/web/feeds/create" ||
      action === "/web/feeds/opml" ||
      action === "/web/items/save" ||
      action === "/web/boards/create"
    ) {
      const banner = document.getElementById("roche-refresh-banner");
      if (banner) {
        if (refreshHideTimer) {
          clearTimeout(refreshHideTimer);
          refreshHideTimer = null;
        }
        const label = banner.querySelector(".js-refresh-label");
        if (isSingleBoardRefresh) {
          banner.dataset.refreshScope = "board";
          if (label) label.textContent = "Refreshing board…";
        } else {
          delete banner.dataset.refreshScope;
          if (label) label.textContent = "Refreshing feeds & metadata in background…";
        }
        banner.classList.remove("is-complete");
        banner.hidden = false;
        banner.setAttribute("data-active", "1");
      }
    }
  });

  /* --- 6. Theme Toggle, Masthead Search & Sleeve Drawers --- */
  const THEME_STORAGE_KEY = "roche_theme_v1";

  const persistThemeCookie = (theme) => {
    if (theme !== "dark" && theme !== "light") return;
    try {
      const securePart = window.location.protocol === "https:" ? "; Secure" : "";
      document.cookie = `roche_theme=${theme}; Path=/; Max-Age=31536000; SameSite=Lax${securePart}`;
    } catch {}
  };

  const applySavedTheme = () => {
    try {
      const saved = localStorage.getItem(THEME_STORAGE_KEY);
      if (saved === "dark" || saved === "light") {
        document.documentElement.setAttribute("data-theme", saved);
        persistThemeCookie(saved);
      }
    } catch {}
  };
  applySavedTheme();

  const scrollActiveNavIntoView = () => {
    const nav = document.getElementById("roche-nav");
    const activeLink = nav?.querySelector("a.is-active");
    if (!nav || !activeLink || nav.scrollWidth <= nav.clientWidth) return;
    const navRect = nav.getBoundingClientRect();
    const linkRect = activeLink.getBoundingClientRect();
    const targetLeft =
      nav.scrollLeft + (linkRect.left - navRect.left) - (nav.clientWidth - linkRect.width) / 2;
    nav.scrollLeft = Math.max(0, Math.min(targetLeft, nav.scrollWidth - nav.clientWidth));
  };

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
        persistThemeCookie(next);
        syncThemeLabel();
      });
    }

    for (const toggleBtn of document.querySelectorAll(".js-toggle-password")) {
      if (toggleBtn.dataset.bound === "1") continue;
      toggleBtn.dataset.bound = "1";
      toggleBtn.addEventListener("click", () => {
        const targetId = toggleBtn.getAttribute("data-target");
        const input = targetId ? document.getElementById(targetId) : null;
        if (!input) return;
        const showing = input.type === "password";
        input.type = showing ? "text" : "password";
        toggleBtn.textContent = showing ? "Hide" : "Show";
        toggleBtn.setAttribute("aria-pressed", showing ? "true" : "false");
        toggleBtn.setAttribute("aria-label", showing ? "Hide pairing code" : "Show pairing code");
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
    if (searchInput && searchInput.dataset.escBound !== "1") {
      searchInput.dataset.escBound = "1";
      searchInput.addEventListener("keydown", (ev) => {
        if (ev.key === "Escape") {
          searchInput.blur();
          if (masthead?.classList.contains("is-search-open")) {
            masthead.classList.remove("is-search-open");
            searchToggle?.setAttribute("aria-expanded", "false");
          }
        }
      });
    }

    /* Quick Log & Shortcuts modals */
    const qlDialog = document.getElementById("roche-quick-log-dialog");
    const qlBtn = document.getElementById("roche-quick-log-btn");
    const kbdDialog = document.getElementById("roche-shortcuts-dialog");
    const kbdBtn = document.getElementById("roche-shortcuts-btn");

    const openQuickLogDialog = (tabName = "media") => {
      if (!qlDialog || typeof qlDialog.showModal !== "function") return;
      const curReturn = `${window.location.pathname}${window.location.search}`;
      for (const f of qlDialog.querySelectorAll(".js-modal-return-form")) {
        const ret = f.querySelector('input[name="return_to"]');
        if (ret) ret.value = curReturn || "/";
      }
      selectQlTab(tabName);
      if (!qlDialog.open) qlDialog.showModal();
      const targetInput =
        tabName === "url"
          ? document.getElementById("qlm-url")
          : document.getElementById("qlm-title");
      targetInput?.focus();
    };

    const selectQlTab = (tabName) => {
      if (!qlDialog) return;
      const isUrl = tabName === "url";
      for (const tab of qlDialog.querySelectorAll(".js-ql-tab")) {
        const active = (tab.getAttribute("data-tab") || "media") === (isUrl ? "url" : "media");
        tab.classList.toggle("is-active", active);
        tab.setAttribute("aria-selected", active ? "true" : "false");
      }
      const mediaPanel = document.getElementById("roche-ql-panel-media");
      const urlPanel = document.getElementById("roche-ql-panel-url");
      if (mediaPanel) mediaPanel.hidden = isUrl;
      if (urlPanel) urlPanel.hidden = !isUrl;
    };

    if (qlDialog && qlDialog.dataset.bound !== "1") {
      qlDialog.dataset.bound = "1";
      for (const tab of qlDialog.querySelectorAll(".js-ql-tab")) {
        tab.addEventListener("click", () => {
          const nextTab = tab.getAttribute("data-tab") || "media";
          selectQlTab(nextTab);
          const firstField =
            nextTab === "url"
              ? document.getElementById("qlm-url")
              : document.getElementById("qlm-title");
          firstField?.focus();
        });
      }
      for (const btn of qlDialog.querySelectorAll(".js-modal-close")) {
        btn.addEventListener("click", () => qlDialog.close());
      }
      qlDialog.addEventListener("click", (ev) => {
        if (ev.target === qlDialog) qlDialog.close();
      });
    }
    if (qlBtn && qlBtn.dataset.bound !== "1") {
      qlBtn.dataset.bound = "1";
      qlBtn.addEventListener("click", () => openQuickLogDialog("media"));
    }

    if (kbdDialog && kbdDialog.dataset.bound !== "1") {
      kbdDialog.dataset.bound = "1";
      for (const btn of kbdDialog.querySelectorAll(".js-modal-close")) {
        btn.addEventListener("click", () => kbdDialog.close());
      }
      kbdDialog.addEventListener("click", (ev) => {
        if (ev.target === kbdDialog) kbdDialog.close();
      });
    }
    if (kbdBtn && kbdBtn.dataset.bound !== "1") {
      kbdBtn.dataset.bound = "1";
      kbdBtn.addEventListener("click", () => {
        if (kbdDialog && typeof kbdDialog.showModal === "function" && !kbdDialog.open) {
          kbdDialog.showModal();
        }
      });
    }

    if (document.body && document.body.dataset.globalKeysBound !== "1") {
      document.body.dataset.globalKeysBound = "1";
      document.addEventListener("keydown", (ev) => {
        if (ev.metaKey || ev.ctrlKey || ev.altKey) return;
        const target = ev.target;
        if (target?.matches?.("input, textarea, select, [contenteditable]")) return;
        const openDialog = document.querySelector("dialog[open]");
        if (openDialog) {
          if (ev.key === "?" && openDialog === kbdDialog) {
            ev.preventDefault();
            kbdDialog.close();
          }
          return;
        }

        const isRankBoard = Boolean(
          document.getElementById("roche-rank-board") || document.querySelector(".js-rank-form")
        );

        if (ev.key === "?") {
          if (kbdDialog && typeof kbdDialog.showModal === "function") {
            ev.preventDefault();
            kbdDialog.showModal();
          }
          return;
        }
        if (ev.key === "c" && !isRankBoard) {
          if (qlDialog && typeof qlDialog.showModal === "function") {
            ev.preventDefault();
            openQuickLogDialog("media");
          }
          return;
        }
        if (ev.key === "/" && !isRankBoard) {
          const streamSearch =
            document.getElementById("roche-inbox-search") ||
            document.getElementById("roche-board-search");
          const input = streamSearch || document.getElementById("roche-global-search");
          if (!input) return;
          ev.preventDefault();
          if (!streamSearch) {
            const hdr = document.getElementById("roche-masthead");
            const searchWrap = document.getElementById("roche-masthead-search");
            if (
              hdr &&
              ((searchWrap && window.getComputedStyle(searchWrap).display === "none") ||
                window.getComputedStyle(input).display === "none")
            ) {
              hdr.classList.add("is-search-open");
              document.getElementById("roche-search-toggle")?.setAttribute("aria-expanded", "true");
            }
          }
          input.focus();
          input.select();
          return;
        }

        if (isRankBoard) return;

        /* Reader shortcuts: [, ], d, e, s, Escape */
        const readerRoot = document.querySelector(".js-reader-root");
        if (readerRoot) {
          if (ev.key === "[") {
            ev.preventDefault();
            document.querySelector(".js-font-dec")?.click();
            return;
          }
          if (ev.key === "]") {
            ev.preventDefault();
            document.querySelector(".js-font-inc")?.click();
            return;
          }
          if (ev.key === "d") {
            const discLink = document.querySelector(".js-reader-discussion-link");
            if (discLink?.href) {
              ev.preventDefault();
              window.open(discLink.href, "_blank", "noopener,noreferrer");
            }
            return;
          }
          if (ev.key === "e") {
            const dismissForm = document.querySelector(
              ".js-reader-dismiss-form, .js-reader-archive-form"
            );
            if (dismissForm) {
              ev.preventDefault();
              if (typeof dismissForm.requestSubmit === "function") dismissForm.requestSubmit();
              else dismissForm.submit();
            }
            return;
          }
          if (ev.key === "s") {
            const starBtn = document.querySelector(".js-star-toggle, .reader-star-btn");
            if (starBtn) {
              ev.preventDefault();
              starBtn.click();
            }
            return;
          }
          if (ev.key === "q") {
            const qForm = document.querySelector(".js-reader-queue-form");
            if (qForm) {
              ev.preventDefault();
              if (typeof qForm.requestSubmit === "function") qForm.requestSubmit();
              else qForm.submit();
            }
            return;
          }
          if (ev.key === "Escape") {
            const openDetails = document.querySelector("#roche-main details[open]:not([data-default-open])");
            if (openDetails) {
              openDetails.open = false;
              return;
            }
            const backLink = document.querySelector(".js-reader-back-link");
            if (backLink?.href) {
              ev.preventDefault();
              if (navigateViaPjaxFn) navigateViaPjaxFn(backLink.href);
              else window.location.href = backLink.href;
            }
            return;
          }
        }

        /* Inbox / Boards / Starred keyboard triage: j/k, Enter/o, d, e, s, q */
        const rows = Array.from(document.querySelectorAll(".js-kbd-row")).filter((r) => !r.hidden);
        if (rows.length > 0) {
          let curIdx = rows.findIndex((r) => r.classList.contains("is-kbd-selected"));
          const selectRow = (idx) => {
            for (const r of rows) r.classList.remove("is-kbd-selected");
            const chosen = rows[idx];
            if (!chosen) return null;
            chosen.classList.add("is-kbd-selected");
            chosen.scrollIntoView?.({ block: "nearest" });
            return chosen;
          };

          if (ev.key === "j") {
            ev.preventDefault();
            selectRow(curIdx < 0 ? 0 : Math.min(rows.length - 1, curIdx + 1));
            return;
          }
          if (ev.key === "k") {
            ev.preventDefault();
            selectRow(curIdx <= 0 ? 0 : curIdx - 1);
            return;
          }
          const activeRow = curIdx >= 0 ? rows[curIdx] : null;
          if (!activeRow) return;
          if (ev.key === "o" || ev.key === "Enter") {
            if (ev.key === "Enter" && target?.closest?.("a, button, summary")) return;
            const link = activeRow.querySelector(".js-kbd-open");
            if (link?.href) {
              ev.preventDefault();
              if (navigateViaPjaxFn) navigateViaPjaxFn(link.href);
              else window.location.href = link.href;
            }
            return;
          }
          if (ev.key === "d") {
            const discLink = activeRow.querySelector(".js-kbd-discuss, .board-comments-link");
            if (discLink?.href) {
              ev.preventDefault();
              window.open(discLink.href, "_blank", "noopener,noreferrer");
            }
            return;
          }
          if (ev.key === "e") {
            const triageForm = activeRow.querySelector(
              '.js-triage-form[data-triage-kind="dismiss"], .js-triage-form[data-triage-kind="mark-new"], .js-triage-form[data-triage-kind="archive"], .js-triage-form[data-triage-kind="unarchive"], .js-triage-form[data-triage-kind="board-dismiss"], .js-triage-form[data-triage-kind="board-restore"]'
            );
            if (triageForm) {
              ev.preventDefault();
              const nextIdx = Math.min(rows.length - 2, curIdx);
              if (typeof triageForm.requestSubmit === "function") triageForm.requestSubmit();
              else triageForm.submit();
              window.requestAnimationFrame(() => {
                const rem = Array.from(document.querySelectorAll(".js-kbd-row")).filter((r) => !r.hidden);
                if (rem.length > 0 && nextIdx >= 0) {
                  for (const r of rem) r.classList.remove("is-kbd-selected");
                  rem[Math.min(rem.length - 1, nextIdx)]?.classList.add("is-kbd-selected");
                }
              });
            }
            return;
          }
          if (ev.key === "s") {
            const starBtn = activeRow.querySelector(".inbox-star-btn");
            if (starBtn) {
              ev.preventDefault();
              starBtn.click();
            }
            return;
          }
          if (ev.key === "q") {
            const qForm = activeRow.querySelector(
              '.js-triage-form[data-triage-kind="queue"], .js-triage-form[data-triage-kind="unqueue"], .js-triage-form[data-triage-kind="board-queue"]'
            );
            if (qForm) {
              ev.preventDefault();
              if (typeof qForm.requestSubmit === "function") qForm.requestSubmit();
              else qForm.submit();
            }
          }
        }
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
    for (const container of document.querySelectorAll(".js-sleeve-drawers, .sleeve-actions")) {
      if (container.dataset.bound === "1") continue;
      container.dataset.bound = "1";
      const drawers = Array.from(
        container.querySelectorAll("details.album-inline-details, details.sleeve-rank-details, details.sleeve-skip-details")
      );
      for (const drawer of drawers) {
        drawer.addEventListener("toggle", () => {
          if (!drawer.open) return;
          for (const other of drawers) {
            if (other !== drawer && other.open) other.open = false;
          }
        });
      }
    }
    if (document.body && document.body.dataset.sleeveEscBound !== "1") {
      document.body.dataset.sleeveEscBound = "1";
      document.addEventListener("keydown", (ev) => {
        if (ev.key !== "Escape") return;
        for (const d of document.querySelectorAll(
          ".js-sleeve-drawers details[open], .sleeve-actions details[open]"
        )) {
          d.open = false;
        }
      });
    }
  };

  const initSidebarFeedFilter = () => {
    const input = document.querySelector(".sidebar-feed-filter");
    const list = document.getElementById("proto-feed-list") || document.getElementById("roche-sidebar-feeds");
    const emptyEl = document.getElementById("roche-sidebar-feeds-empty");
    if (!input || !list || input.dataset.bound === "1") return;
    input.dataset.bound = "1";
    input.addEventListener("input", () => {
      const q = input.value.trim().toLowerCase();
      let visible = 0;
      for (const li of list.children) {
        const name = (li.getAttribute("data-feed-name") || li.textContent || "").toLowerCase();
        const hide = Boolean(q && !name.includes(q));
        li.hidden = hide;
        if (!hide) visible += 1;
      }
      if (emptyEl) emptyEl.hidden = !(q && visible === 0);
    });
  };

  const initMobileFeedSelect = () => {
    for (const sel of document.querySelectorAll(".js-mobile-feed-select")) {
      if (sel.dataset.bound === "1") continue;
      sel.dataset.bound = "1";
      sel.addEventListener("change", () => {
        const dest = sel.value;
        if (!dest) return;
        if (navigateViaPjaxFn) navigateViaPjaxFn(dest);
        else window.location.href = dest;
      });
    }
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

  let readerExtrasCleanup = null;

  const initReaderExtras = () => {
    if (readerExtrasCleanup) {
      readerExtrasCleanup();
      readerExtrasCleanup = null;
    }
    const root = document.querySelector(".js-reader-root");
    if (!root) {
      document.getElementById("roche-read-progress")?.remove();
      return;
    }
    const cleanups = [];

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

    // 3. Auto-build Table of Contents for both desktop rail and mobile drawer
    const tocList = document.getElementById("roche-reader-toc");
    const tocGroup = document.getElementById("roche-reader-toc-group");
    const mobileTocList = document.querySelector(".js-reader-mobile-toc");
    const mobileTocGroup = document.querySelector(".js-reader-mobile-toc-group");
    if ((tocList || mobileTocList) && prose && root.dataset.tocBuilt !== "1") {
      root.dataset.tocBuilt = "1";
      const candidateHeadings = Array.from(prose.querySelectorAll("h2, h3, h4")).filter(
        (h) => (h.textContent || "").trim().length > 0
      );
      const minLevel = candidateHeadings.reduce(
        (acc, h) => Math.min(acc, Number.parseInt(h.tagName.slice(1), 10) || 6),
        6
      );
      const headings = candidateHeadings.filter((h) => {
        const lvl = Number.parseInt(h.tagName.slice(1), 10) || 6;
        return lvl <= minLevel + 1;
      });
      if (headings.length >= 2) {
        const linksById = new Map();
        const appendTocItem = (targetList, h, lvl, closeMobileDrawer) => {
          const li = document.createElement("li");
          if (lvl > minLevel) li.className = "toc-sub";
          const a = document.createElement("a");
          a.href = `#${h.id}`;
          a.textContent = h.textContent.trim();
          if (closeMobileDrawer) {
            a.addEventListener("click", () => {
              const d = targetList.closest("details");
              if (d) d.open = false;
            });
          }
          li.appendChild(a);
          targetList.appendChild(li);
          const listArr = linksById.get(h.id) || [];
          listArr.push(a);
          linksById.set(h.id, listArr);
        };
        headings.forEach((h, i) => {
          if (!h.id) h.id = `sec-${i + 1}`;
          const lvl = Number.parseInt(h.tagName.slice(1), 10) || minLevel;
          if (tocList) appendTocItem(tocList, h, lvl, false);
          if (mobileTocList) appendTocItem(mobileTocList, h, lvl, true);
        });
        const markCurrentToc = (activeId) => {
          for (const [id, anchors] of linksById.entries()) {
            const isCur = id === activeId;
            for (const anchor of anchors) {
              anchor.classList.toggle("is-current", isCur);
              if (isCur) anchor.setAttribute("aria-current", "true");
              else anchor.removeAttribute("aria-current");
            }
          }
        };
        markCurrentToc(headings[0].id);
        if (typeof IntersectionObserver === "function") {
          const io = new IntersectionObserver(
            (entries) => {
              const visible = entries
                .filter((e) => e.isIntersecting)
                .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
              if (visible.length > 0 && visible[0].target.id) {
                markCurrentToc(visible[0].target.id);
              }
            },
            { rootMargin: "0px 0px -70% 0px", threshold: 0.1 }
          );
          for (const h of headings) io.observe(h);
          cleanups.push(() => io.disconnect());
        }
        if (tocGroup) tocGroup.hidden = false;
        if (mobileTocGroup) mobileTocGroup.hidden = false;
      } else {
        if (tocGroup) tocGroup.hidden = true;
        if (mobileTocGroup) mobileTocGroup.hidden = true;
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

    readerExtrasCleanup = () => {
      for (const fn of cleanups) fn();
    };
  };

  const syncBoardBadge = (row, selector, present, className, text, extraAttrs = null) => {
    if (!row || !row.classList?.contains("board-row")) return;
    const byline = row.querySelector(".board-byline");
    if (!byline) return;
    const existing = byline.querySelector(selector);
    if (present) {
      if (!existing) {
        const badge = document.createElement("span");
        badge.className = className;
        badge.textContent = text;
        if (extraAttrs) {
          for (const [k, v] of Object.entries(extraAttrs)) badge.setAttribute(k, v);
        }
        byline.prepend(badge);
      }
    } else if (existing) {
      existing.remove();
    }
  };

  const syncBoardCounts = (boardId, visibleCount, dismissedCount) => {
    if (Number.isFinite(visibleCount)) {
      for (const el of document.querySelectorAll(".js-board-active-count")) {
        el.textContent = String(Math.max(0, visibleCount));
      }
      if (boardId) {
        for (const el of document.querySelectorAll(".js-board-sidebar-count")) {
          if (el.getAttribute("data-board-id") === boardId) {
            el.textContent = visibleCount > 0 ? String(visibleCount) : "";
          }
        }
      }
    }
    if (Number.isFinite(dismissedCount)) {
      for (const el of document.querySelectorAll(".js-board-dismissed-count")) {
        el.textContent = String(Math.max(0, dismissedCount));
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
      if (form?.dataset.inflight === "1") {
        ev.preventDefault();
        return;
      }
      const prevStarred = btn.getAttribute("aria-pressed") === "true";
      const nextStarred = !prevStarred;
      const isReaderGroup =
        btn.classList.contains("reader-star-btn") || btn.classList.contains("js-star-toggle");
      const targets = isReaderGroup
        ? Array.from(document.querySelectorAll(".reader-star-btn, .js-star-toggle"))
        : [btn];

      const applyStarState = (starred) => {
        for (const el of targets) {
          el.classList.toggle("is-starred", starred);
          el.setAttribute("aria-pressed", starred ? "true" : "false");
          const curLabel = el.getAttribute("aria-label") || "";
          if (curLabel.startsWith("Star ")) {
            el.setAttribute("aria-label", starred ? `Unstar ${curLabel.slice(5)}` : curLabel);
          } else if (curLabel.startsWith("Unstar ")) {
            el.setAttribute("aria-label", starred ? curLabel : `Star ${curLabel.slice(7)}`);
          } else if (curLabel) {
            el.setAttribute("aria-label", starred ? "Unstar" : "Star");
          }
          const glyphHost = el.querySelector("span[aria-hidden]") || el;
          glyphHost.textContent = starred ? "★" : "☆";
        }
      };

      applyStarState(nextStarred);

      if (!form) return;
      const action = form.getAttribute("action") || "";
      if (!action.startsWith("/web/")) return;
      ev.preventDefault();
      form.dataset.inflight = "1";
      const isBoardPromote = action.startsWith("/web/boards/") && action.endsWith("/promote");
      const csrf = getCsrfToken();
      const fd = new FormData(form);
      const params = new URLSearchParams();
      for (const [k, v] of fd.entries()) {
        if (typeof v === "string") params.append(k, v);
      }
      const headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "X-Roche-Csrf": csrf,
      };
      if (isBoardPromote) {
        headers["X-Roche-Ajax"] = "1";
        headers.Accept = "application/json";
      }
      fetch(action, {
        method: "POST",
        headers,
        body: params.toString(),
        credentials: "same-origin",
      })
        .then(async (resp) => {
          if (!resp.ok || isLoginUrl(resp.url)) {
            applyStarState(prevStarred);
            showToast("Could not update star — please try again.", { isError: true });
            return;
          }
          if (isBoardPromote) {
            try {
              const data = await resp.json();
              if (data?.star_action) {
                for (const el of targets) {
                  const targetForm = el.closest("form");
                  if (targetForm) {
                    targetForm.setAttribute("action", data.star_action);
                    targetForm.querySelector('input[name="action"]')?.remove();
                  }
                }
              }
            } catch {}
          }
        })
        .catch(() => {
          applyStarState(prevStarred);
          showToast("Network error while updating star.", { isError: true });
        })
        .finally(() => {
          delete form.dataset.inflight;
        });
    });
  };

  const initInPlaceTriage = () => {
    if (document.body?.dataset.triageDelegationBound === "1") return;
    if (document.body) document.body.dataset.triageDelegationBound = "1";

    const adjustUnreadCounters = (delta) => {
      if (!delta) return;
      for (const badge of document.querySelectorAll(".js-unread-count")) {
        const cur = Number.parseInt(badge.textContent || "", 10);
        if (Number.isFinite(cur)) {
          badge.textContent = String(Math.max(0, cur + delta));
        }
      }
    };

    const postSimpleWebAction = (actionUrl, extraPairs = [], asJson = false) => {
      const csrf = getCsrfToken();
      const params = new URLSearchParams();
      params.append("_csrf", csrf);
      for (const [k, v] of extraPairs) params.append(k, v);
      const headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "X-Roche-Csrf": csrf,
      };
      if (asJson) {
        headers["X-Roche-Ajax"] = "1";
        headers.Accept = "application/json";
      }
      return fetch(actionUrl, {
        method: "POST",
        headers,
        body: params.toString(),
        credentials: "same-origin",
      });
    };

    const setQueueFormState = (form, nowQueued) => {
      const btn = form.querySelector("button[type='submit']");
      const title = form.getAttribute("data-triage-title") || "item";
      const curAction = form.getAttribute("action") || "";
      const undoAction = form.getAttribute("data-undo-action") || "";
      if (undoAction) {
        form.setAttribute("action", undoAction);
        form.setAttribute("data-undo-action", curAction);
      }
      form.setAttribute("data-triage-kind", nowQueued ? "unqueue" : "queue");
      let slugInput = form.querySelector('input[name="list_slug_or_id"]');
      if (nowQueued) {
        slugInput?.remove();
      } else if (!slugInput) {
        slugInput = document.createElement("input");
        slugInput.type = "hidden";
        slugInput.name = "list_slug_or_id";
        slugInput.value = WANT_TO_READ_COLLECTION_ID;
        form.appendChild(slugInput);
      }
      if (btn) {
        btn.classList.toggle("is-in-queue", nowQueued);
        btn.setAttribute(
          "aria-label",
          nowQueued ? `In Queue — remove ${title} from Queue` : `Add ${title} to Queue`
        );
        btn.setAttribute("title", nowQueued ? "Remove from Queue" : "Add to Queue");
        btn.textContent = nowQueued ? "✓ In Queue" : "+ Queue";
      }
      syncBoardBadge(
        form.closest(".board-row"),
        ".js-board-queue-badge",
        nowQueued,
        "badge badge-queued js-board-queue-badge",
        "In Queue"
      );
    };

    document.addEventListener("submit", (ev) => {
      if (ev.defaultPrevented) return;
      const form = ev.target;
      if (!form || !form.classList?.contains("js-triage-form")) return;
      const action = form.getAttribute("action") || "";
      if (!action.startsWith("/web/")) return;

      ev.preventDefault();
      const kind = form.getAttribute("data-triage-kind") || "";
      const title = form.getAttribute("data-triage-title") || "item";
      const undoAction = form.getAttribute("data-undo-action") || "";
      const row = form.closest(".js-triage-row");

      const fd = new FormData(form);
      const params = new URLSearchParams();
      for (const [k, v] of fd.entries()) {
        if (typeof v === "string") params.append(k, v);
      }

      const sendRequest = (asJson = false) => {
        const headers = {
          "Content-Type": "application/x-www-form-urlencoded",
          "X-Roche-Csrf": getCsrfToken(),
        };
        if (asJson) {
          headers["X-Roche-Ajax"] = "1";
          headers.Accept = "application/json";
        }
        return fetch(action, {
          method: "POST",
          headers,
          body: params.toString(),
          credentials: "same-origin",
        });
      };

      if (kind === "board-queue") {
        if (form.dataset.inflight === "1") return;
        form.dataset.inflight = "1";
        const btn = form.querySelector("button[type='submit']");
        const setBoardQueueBtn = (queued) => {
          if (btn) {
            btn.classList.toggle("is-in-queue", queued);
            btn.setAttribute(
              "aria-label",
              queued ? `In Queue — remove ${title} from Queue` : `Add ${title} to Queue`
            );
            btn.setAttribute("title", queued ? "Remove from Queue" : "Add to Queue");
            btn.textContent = queued ? "✓ In Queue" : "+ Queue";
          }
          syncBoardBadge(
            row,
            ".js-board-queue-badge",
            queued,
            "badge badge-queued js-board-queue-badge",
            "In Queue"
          );
        };
        setBoardQueueBtn(true);
        sendRequest(true)
          .then(async (resp) => {
            if (!resp.ok || isLoginUrl(resp.url)) {
              setBoardQueueBtn(false);
              showToast("Could not add to Queue — please try again.", { isError: true });
              return;
            }
            let data = null;
            try {
              data = await resp.json();
            } catch {}
            if (data?.unqueue_action && data?.queue_action) {
              form.setAttribute("action", data.unqueue_action);
              form.setAttribute("data-undo-action", data.queue_action);
              form.setAttribute("data-triage-kind", "unqueue");
              form.querySelector('input[name="action"]')?.remove();
            }
            showToast(`Added "${title}" to Queue`, {
              onUndo: data?.unqueue_action
                ? () => {
                    const revAction = form.getAttribute("action") || data.unqueue_action;
                    setQueueFormState(form, false);
                    postSimpleWebAction(revAction)
                      .then((undoResp) => {
                        if (!undoResp.ok || isLoginUrl(undoResp.url)) {
                          setQueueFormState(form, true);
                          showToast("Could not undo Queue change.", { isError: true });
                        }
                      })
                      .catch(() => {
                        setQueueFormState(form, true);
                        showToast("Network error while undoing Queue change.", { isError: true });
                      });
                  }
                : undefined,
            });
          })
          .catch(() => {
            setBoardQueueBtn(false);
            showToast("Network error while updating Queue.", { isError: true });
          })
          .finally(() => {
            delete form.dataset.inflight;
          });
        return;
      }

      if (kind === "board-dismiss" || kind === "board-restore") {
        if (!row) {
          form.submit();
          return;
        }
        const list = document.getElementById("roche-board-list");
        const boardId = row.getAttribute("data-board-id") || list?.getAttribute("data-board-id") || "";
        const isDismiss = kind === "board-dismiss";
        const isLiveRow = row.getAttribute("data-live-row") === "1";
        const shouldBackfill = Boolean(isDismiss && list && !isLiveRow);
        row.hidden = true;
        row.dataset.dismissedLocal = "1";

        const syncDismissedEmptyState = () => {
          if (!list || list.getAttribute("data-view") !== "dismissed") return;
          const emptyDismissed = document.getElementById("roche-board-dismissed-empty");
          const remaining = list.querySelectorAll('.board-row:not([data-dismissed-local="1"])').length;
          list.hidden = remaining === 0;
          if (emptyDismissed) emptyDismissed.hidden = remaining > 0;
        };
        syncDismissedEmptyState();

        let pendingReleased = !shouldBackfill;
        const releasePending = () => {
          if (pendingReleased) return;
          pendingReleased = true;
          if (list) {
            const cur = Number.parseInt(list.dataset.pendingBackfills || "0", 10) || 0;
            list.dataset.pendingBackfills = String(Math.max(0, cur - 1));
          }
        };

        if (shouldBackfill) {
          const baseOffset = Number.parseInt(list.getAttribute("data-base-offset") || "0", 10) || 0;
          const remainingBuffered = list.querySelectorAll(
            '.board-row:not([data-dismissed-local="1"]):not([data-live-row="1"])'
          ).length;
          const pendingBefore = Number.parseInt(list.dataset.pendingBackfills || "0", 10) || 0;
          list.dataset.pendingBackfills = String(pendingBefore + 1);
          params.set("backfill_offset", String(baseOffset + remainingBuffered + pendingBefore));
        } else if (isDismiss && isLiveRow) {
          params.set("skip_backfill", "1");
        }

        sendRequest(true)
          .then(async (resp) => {
            if (!resp.ok || isLoginUrl(resp.url)) {
              releasePending();
              row.hidden = false;
              delete row.dataset.dismissedLocal;
              syncDismissedEmptyState();
              showToast("Action failed — please try again.", { isError: true });
              return;
            }
            let data = null;
            try {
              data = await resp.json();
            } catch {}
            releasePending();
            if (data) {
              syncBoardCounts(boardId, data.visible_count, data.dismissed_count);
            }
            let backfilledRow = null;
            let insertLoadMoreEpoch = 0;
            if (shouldBackfill && data?.replacement_html) {
              const repId = data.replacement_external_id || "";
              const alreadyPresent =
                repId &&
                Array.from(list.querySelectorAll(".board-row")).some(
                  (r) => r.getAttribute("data-external-id") === repId
                );
              if (!alreadyPresent) {
                const tpl = document.createElement("template");
                tpl.innerHTML = data.replacement_html.trim();
                const newRow = tpl.content.firstElementChild;
                if (newRow) {
                  const firstLive = list.querySelector('.board-row[data-live-row="1"]');
                  if (firstLive) list.insertBefore(newRow, firstLive);
                  else list.appendChild(newRow);
                  backfilledRow = newRow;
                  insertLoadMoreEpoch = Number.parseInt(list.dataset.loadMoreCount || "0", 10) || 0;
                  applyBoardFilterFn?.();
                }
              }
            }
            const verb = isDismiss ? "Dismissed" : "Restored";
            showToast(`${verb} "${title}"`, {
              onUndo: undoAction
                ? () => {
                    row.hidden = false;
                    delete row.dataset.dismissedLocal;
                    syncDismissedEmptyState();
                    let removedBackfill = false;
                    if (backfilledRow && backfilledRow.isConnected && list) {
                      const curEpoch = Number.parseInt(list.dataset.loadMoreCount || "0", 10) || 0;
                      const nextSib = backfilledRow.nextElementSibling;
                      const hasBufferedAfter = Boolean(
                        nextSib && nextSib.getAttribute("data-live-row") !== "1"
                      );
                      if (curEpoch === insertLoadMoreEpoch && !hasBufferedAfter) {
                        backfilledRow.remove();
                        removedBackfill = true;
                      }
                    }
                    applyBoardFilterFn?.();
                    postSimpleWebAction(undoAction, [], true)
                      .then(async (undoResp) => {
                        if (!undoResp.ok || isLoginUrl(undoResp.url)) {
                          row.hidden = true;
                          row.dataset.dismissedLocal = "1";
                          syncDismissedEmptyState();
                          if (removedBackfill && backfilledRow && list) list.appendChild(backfilledRow);
                          showToast("Could not undo action.", { isError: true });
                          return;
                        }
                        try {
                          const undoData = await undoResp.json();
                          syncBoardCounts(boardId, undoData.visible_count, undoData.dismissed_count);
                        } catch {}
                      })
                      .catch(() => {
                        row.hidden = true;
                        row.dataset.dismissedLocal = "1";
                        syncDismissedEmptyState();
                        if (removedBackfill && backfilledRow && list) list.appendChild(backfilledRow);
                        showToast("Network error while undoing action.", { isError: true });
                      });
                  }
                : undefined,
            });
          })
          .catch(() => {
            row.hidden = false;
            delete row.dataset.dismissedLocal;
            syncDismissedEmptyState();
            showToast("Network error — please try again.", { isError: true });
          })
          .finally(() => {
            releasePending();
          });
        return;
      }

      if (kind === "queue" || kind === "unqueue") {
        if (form.dataset.inflight === "1") return;
        form.dataset.inflight = "1";
        const becomingQueued = kind === "queue";
        setQueueFormState(form, becomingQueued);
        sendRequest()
          .then((resp) => {
            if (!resp.ok || isLoginUrl(resp.url)) {
              setQueueFormState(form, !becomingQueued);
              showToast("Could not update Queue — please try again.", { isError: true });
              return;
            }
            showToast(becomingQueued ? `Added "${title}" to Queue` : `Removed "${title}" from Queue`, {
              onUndo: () => {
                const revAction = form.getAttribute("action") || "";
                const revExtras = !becomingQueued
                  ? [["list_slug_or_id", WANT_TO_READ_COLLECTION_ID]]
                  : [];
                setQueueFormState(form, !becomingQueued);
                postSimpleWebAction(revAction, revExtras)
                  .then((undoResp) => {
                    if (!undoResp.ok || isLoginUrl(undoResp.url)) {
                      setQueueFormState(form, becomingQueued);
                      showToast("Could not undo Queue change.", { isError: true });
                    }
                  })
                  .catch(() => {
                    setQueueFormState(form, becomingQueued);
                    showToast("Network error while undoing Queue change.", { isError: true });
                  });
              },
            });
          })
          .catch(() => {
            setQueueFormState(form, !becomingQueued);
            showToast("Network error while updating Queue.", { isError: true });
          })
          .finally(() => {
            delete form.dataset.inflight;
          });
        return;
      }

      const inboxStatusParam =
        window.location.pathname === "/inbox"
          ? new URLSearchParams(window.location.search).get("status")
          : null;
      const isDismissAction = kind === "dismiss" || kind === "archive";
      const isRestoreAction = kind === "mark-new" || kind === "unarchive";
      if (!row || (inboxStatusParam === "all" && (isDismissAction || isRestoreAction))) {
        form.submit();
        return;
      }

      const wasFinishedRow = row.classList.contains("is-finished");
      const unreadDelta = isDismissAction
        ? wasFinishedRow
          ? 0
          : -1
        : isRestoreAction
          ? 1
          : 0;
      row.hidden = true;
      adjustUnreadCounters(unreadDelta);

      sendRequest()
        .then((resp) => {
          if (!resp.ok || isLoginUrl(resp.url)) {
            row.hidden = false;
            adjustUnreadCounters(-unreadDelta);
            showToast("Action failed — please try again.", { isError: true });
            return;
          }
          const verb = isDismissAction
            ? "Dismissed"
            : isRestoreAction
              ? "Marked new"
              : "Marked finished";
          showToast(`${verb} "${title}"`, {
            onUndo: undoAction
              ? () => {
                  row.hidden = false;
                  adjustUnreadCounters(-unreadDelta);
                  postSimpleWebAction(undoAction)
                    .then((undoResp) => {
                      if (!undoResp.ok || isLoginUrl(undoResp.url)) {
                        row.hidden = true;
                        adjustUnreadCounters(unreadDelta);
                        showToast("Could not undo action.", { isError: true });
                      }
                    })
                    .catch(() => {
                      row.hidden = true;
                      adjustUnreadCounters(unreadDelta);
                      showToast("Network error while undoing action.", { isError: true });
                    });
                }
              : undefined,
          });
        })
        .catch(() => {
          row.hidden = false;
          adjustUnreadCounters(-unreadDelta);
          showToast("Network error — please try again.", { isError: true });
        });
    });
  };

  const initFeedsFilter = () => {
    const list = document.getElementById("roche-feeds-list");
    if (!list || list.dataset.bound === "1") return;
    list.dataset.bound = "1";
    const input = document.getElementById("roche-feeds-search");
    const kindSelect = document.getElementById("roche-feeds-kind");
    const emptyEl = document.getElementById("roche-feeds-empty");
    const clearBtn = document.querySelector(".js-feeds-clear");
    const pills = Array.from(document.querySelectorAll(".js-feeds-pill"));
    const statBtns = Array.from(document.querySelectorAll(".js-feeds-stat-btn"));

    const initialPill = pills.find((p) => p.classList.contains("is-active"));
    let activeStatus = initialPill?.getAttribute("data-status") || "all";

    const buildFeedsUrl = () => {
      const params = new URLSearchParams();
      if (activeStatus && activeStatus !== "all") params.set("status", activeStatus);
      const kindVal = (kindSelect?.value || "all").toLowerCase();
      if (kindVal && kindVal !== "all") params.set("kind", kindVal);
      const qVal = (input?.value || "").trim();
      if (qVal) params.set("q", qVal);
      const qs = params.toString();
      return qs ? `/feeds?${qs}` : "/feeds";
    };

    const syncPreserveForms = () => {
      const dest = buildFeedsUrl();
      for (const f of document.querySelectorAll(".js-feeds-preserve-form")) {
        const ret = f.querySelector('input[name="return_to"]');
        if (ret) ret.value = dest;
      }
      if (window.location.pathname === "/feeds" && typeof window.history?.replaceState === "function") {
        try {
          window.history.replaceState(window.history.state, "", dest);
        } catch {}
      }
    };

    const setActiveStatus = (nextStatus) => {
      activeStatus = nextStatus || "all";
      for (const p of pills) {
        const isCur = (p.getAttribute("data-status") || "all") === activeStatus;
        p.classList.toggle("is-active", isCur);
        p.setAttribute("aria-pressed", isCur ? "true" : "false");
      }
      for (const b of statBtns) {
        const isCur = (b.getAttribute("data-status") || "") === activeStatus;
        b.classList.toggle("is-active", isCur);
      }
    };

    const apply = (syncUrl = true) => {
      const q = (input?.value || "").trim().toLowerCase();
      const activeKind = (kindSelect?.value || "all").toLowerCase();
      let visible = 0;
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
        const show = matchStatus && matchKind && matchQuery;
        row.hidden = !show;
        if (show) visible += 1;
      }
      if (emptyEl) emptyEl.hidden = !(list.children.length > 0 && visible === 0);
      if (syncUrl) syncPreserveForms();
    };

    input?.addEventListener("input", () => apply(true));
    kindSelect?.addEventListener("change", () => apply(true));
    for (const pill of pills) {
      pill.addEventListener("click", () => {
        setActiveStatus(pill.getAttribute("data-status") || "all");
        apply(true);
      });
    }
    for (const statBtn of statBtns) {
      statBtn.addEventListener("click", () => {
        const st = statBtn.getAttribute("data-status") || "all";
        setActiveStatus(activeStatus === st ? "all" : st);
        apply(true);
      });
    }
    clearBtn?.addEventListener("click", () => {
      setActiveStatus("all");
      if (kindSelect) kindSelect.value = "all";
      if (input) input.value = "";
      apply(true);
    });
    setActiveStatus(activeStatus);
    apply(false);
  };

  const initListFilter = () => {
    const tbody = document.getElementById("roche-ranked-tbody");
    if (!tbody || tbody.dataset.bound === "1") return;
    tbody.dataset.bound = "1";
    const input = document.getElementById("roche-list-search");
    const emptyEl = document.getElementById("roche-list-empty");
    const clearBtn = document.querySelector(".js-list-clear");

    const apply = () => {
      const q = (input?.value || "").trim().toLowerCase();
      let visible = 0;
      for (const row of tbody.children) {
        const hay = (row.getAttribute("data-work-search") || "").toLowerCase();
        const show = !q || hay.includes(q);
        row.hidden = !show;
        if (show) visible += 1;
      }
      if (emptyEl) emptyEl.hidden = !(tbody.children.length > 0 && visible === 0);
    };

    input?.addEventListener("input", apply);
    clearBtn?.addEventListener("click", () => {
      if (input) input.value = "";
      apply();
    });
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

  const initDiaryFilter = () => {
    const input = document.getElementById("roche-diary-search");
    const root = document.getElementById("roche-diary-months");
    const emptyEl = document.getElementById("roche-diary-empty");
    const clearBtn = document.querySelector(".js-diary-clear");
    if (!input || !root || input.dataset.bound === "1") return;
    input.dataset.bound = "1";
    const apply = () => {
      const q = input.value.trim().toLowerCase();
      let visibleMonths = 0;
      for (const sec of root.querySelectorAll(".diary-month")) {
        let visible = 0;
        for (const row of sec.querySelectorAll(".diary-entry-row")) {
          const hay = (row.getAttribute("data-diary-search") || "").toLowerCase();
          const hide = Boolean(q && !hay.includes(q));
          row.hidden = hide;
          if (!hide) visible += 1;
        }
        sec.hidden = visible === 0;
        if (visible > 0) visibleMonths += 1;
      }
      if (emptyEl) emptyEl.hidden = !(q && visibleMonths === 0);
    };
    input.addEventListener("input", apply);
    clearBtn?.addEventListener("click", () => {
      input.value = "";
      apply();
    });
  };

  const initEntryEnrichment = () => {
    const banner = document.querySelector(".js-entry-enrich[data-entry-id]");
    if (!banner || banner.dataset.triggered === "1") return;
    banner.dataset.triggered = "1";
    const entryId = banner.getAttribute("data-entry-id");
    if (!entryId) return;
    const expectedPath = window.location.pathname;
    fetch(`/web/entries/${encodeURIComponent(entryId)}/enrich`, {
      method: "POST",
      headers: { "X-Roche-Csrf": getCsrfToken() },
      credentials: "same-origin",
    })
      .then((resp) => (resp.ok ? resp.json() : null))
      .then(async (data) => {
        if (window.location.pathname !== expectedPath) return;
        if (data?.enriched && !hasDirtyFormOrOpenDetails()) {
          const pageResp = await fetch(`${window.location.pathname}${window.location.search}`, {
            credentials: "same-origin",
          });
          if (pageResp.ok && window.location.pathname === expectedPath && !hasDirtyFormOrOpenDetails()) {
            const html = await pageResp.text();
            const doc = new DOMParser().parseFromString(html, "text/html");
            const nextMain = doc.getElementById("roche-main");
            const curMain = document.getElementById("roche-main");
            if (nextMain && curMain) {
              syncCsrfFromDoc(doc);
              curMain.replaceChildren(...Array.from(nextMain.childNodes));
              initPageWidgets();
              return;
            }
          }
        }
        banner.remove();
      })
      .catch(() => {
        banner.remove();
      });
  };

  const initLogStatusRankSync = () => {
    for (const sel of document.querySelectorAll(".js-log-status-select")) {
      if (sel.dataset.bound === "1") continue;
      sel.dataset.bound = "1";
      const form = sel.closest("form");
      const rankCheck = form?.querySelector(".js-log-rank-check");
      if (!rankCheck) continue;
      const sync = () => {
        const val = (sel.value || "").toLowerCase();
        const canRank = val === "consumed" || val === "want";
        rankCheck.disabled = !canRank;
        if (!canRank) rankCheck.checked = false;
        rankCheck.closest(".field-check")?.classList.toggle("is-disabled", !canRank);
      };
      sel.addEventListener("change", sync);
      sync();
    }
  };

  const initCopyButtons = () => {
    for (const btn of document.querySelectorAll(".js-copy-text[data-copy-text]")) {
      if (btn.dataset.bound === "1") continue;
      btn.dataset.bound = "1";
      btn.addEventListener("click", async () => {
        const fromId = btn.getAttribute("data-copy-from");
        const fromEl = fromId ? document.getElementById(fromId) : null;
        const text = (fromEl && "value" in fromEl ? fromEl.value.trim() : "")
          || btn.getAttribute("data-copy-text")
          || "";
        if (!text || !navigator.clipboard?.writeText) return;
        const orig = btn.dataset.origLabel || (btn.textContent || "Copy");
        btn.dataset.origLabel = orig;
        try {
          await navigator.clipboard.writeText(text);
          btn.textContent = "Copied ✓";
          if (btn.dataset.copyTimer) window.clearTimeout(Number(btn.dataset.copyTimer));
          btn.dataset.copyTimer = String(window.setTimeout(() => {
            btn.textContent = orig;
            delete btn.dataset.copyTimer;
          }, 1800));
        } catch {}
      });
    }
  };

  const initBoardWidgets = () => {
    const list = document.getElementById("roche-board-list");
    const input = document.getElementById("roche-board-search");
    const emptyEl = document.getElementById("roche-board-filter-empty");
    const clearBtn = document.querySelector(".js-board-filter-clear");

    if (list && list.dataset.boardBound !== "1") {
      list.dataset.boardBound = "1";
      const applyFilter = () => {
        const q = (input?.value || "").trim().toLowerCase();
        let activeCount = 0;
        let visibleCount = 0;
        for (const row of list.querySelectorAll(".board-row")) {
          if (row.dataset.dismissedLocal === "1") {
            row.hidden = true;
            continue;
          }
          activeCount += 1;
          const hay = (row.getAttribute("data-board-search") || row.textContent || "").toLowerCase();
          const show = !q || hay.includes(q);
          row.hidden = !show;
          if (show) visibleCount += 1;
        }
        if (emptyEl) emptyEl.hidden = !(q && activeCount > 0 && visibleCount === 0);
      };
      applyBoardFilterFn = applyFilter;

      if (input && input.dataset.bound !== "1") {
        input.dataset.bound = "1";
        input.addEventListener("input", applyFilter);
        input.addEventListener("keydown", (ev) => {
          if (ev.key === "Escape") {
            if (input.value) {
              input.value = "";
              applyFilter();
            }
            input.blur();
          }
        });
      }
      if (clearBtn && clearBtn.dataset.bound !== "1") {
        clearBtn.dataset.bound = "1";
        clearBtn.addEventListener("click", () => {
          const clearHref = clearBtn.getAttribute("data-clear-href");
          if (clearHref && new URLSearchParams(window.location.search).has("q")) {
            if (navigateViaPjaxFn) navigateViaPjaxFn(clearHref);
            else window.location.href = clearHref;
            return;
          }
          if (input) input.value = "";
          applyFilter();
        });
      }
    } else if (!list) {
      applyBoardFilterFn = null;
    }

    const loadMoreLink = document.querySelector(".js-board-load-more");
    if (loadMoreLink && list && loadMoreLink.dataset.bound !== "1") {
      loadMoreLink.dataset.bound = "1";
      loadMoreLink.addEventListener("click", async (ev) => {
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
        const loadUrl = loadMoreLink.getAttribute("data-load-url");
        if (!loadUrl) return;
        ev.preventDefault();
        if (loadMoreLink.dataset.loading === "1") return;
        loadMoreLink.dataset.loading = "1";
        const origLabel = loadMoreLink.textContent || "Load more stories";
        loadMoreLink.textContent = "Loading…";
        loadMoreLink.setAttribute("aria-busy", "true");
        try {
          const resp = await fetch(loadUrl, {
            headers: {
              Accept: "application/json",
              "X-Roche-Ajax": "1",
            },
            credentials: "same-origin",
          });
          if (resp.redirected && isLoginUrl(resp.url)) {
            window.location.href = resp.url;
            return;
          }
          if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
          const data = await resp.json();
          const existingIds = new Set(
            Array.from(list.querySelectorAll(".board-row"))
              .map((r) => r.getAttribute("data-external-id") || "")
              .filter(Boolean)
          );
          if (data?.rows_html) {
            const tpl = document.createElement("template");
            tpl.innerHTML = data.rows_html;
            const frag = document.createDocumentFragment();
            for (const node of Array.from(tpl.content.children)) {
              const extId = node.getAttribute?.("data-external-id") || "";
              if (extId && existingIds.has(extId)) continue;
              if (extId) existingIds.add(extId);
              frag.appendChild(node);
            }
            list.appendChild(frag);
            const curEpoch = Number.parseInt(list.dataset.loadMoreCount || "0", 10) || 0;
            list.dataset.loadMoreCount = String(curEpoch + 1);
            applyBoardFilterFn?.();
            initFaviconFallbacks();
          }
          if (data?.has_more && data?.next_href && data?.next_load_url) {
            loadMoreLink.href = data.next_href;
            loadMoreLink.setAttribute("data-load-url", data.next_load_url);
            loadMoreLink.textContent = data.next_label || "Load more stories";
          } else {
            const endNote = document.createElement("span");
            endNote.className = "meta board-end-note";
            endNote.textContent = "End of board";
            loadMoreLink.replaceWith(endNote);
          }
        } catch {
          loadMoreLink.textContent = origLabel;
          showToast("Could not load more stories — please try again.", { isError: true });
        } finally {
          delete loadMoreLink.dataset.loading;
          loadMoreLink.removeAttribute("aria-busy");
        }
      });
    }

    if (document.body && document.body.dataset.boardSettingsBound !== "1") {
      document.body.dataset.boardSettingsBound = "1";
      document.addEventListener("click", (ev) => {
        for (const d of document.querySelectorAll(".board-settings-details[open]")) {
          if (!d.contains(ev.target)) d.open = false;
        }
      });
      document.addEventListener("keydown", (ev) => {
        if (ev.key !== "Escape") return;
        for (const d of document.querySelectorAll(".board-settings-details[open]")) {
          d.open = false;
        }
      });
    }
  };

  const initPageWidgets = () => {
    scrollActiveNavIntoView();
    syncPlayButtonsFn?.();
    initReaderProgress();
    initReaderExtras();
    initRankBoard();
    initOpmlFileHelper();
    initRefreshBanner();
    initSleeveDrawers();
    initSidebarFeedFilter();
    initMobileFeedSelect();
    initFeedsFilter();
    initBoardWidgets();
    initListFilter();
    initListsViewToggle();
    initDiaryFilter();
    initEntryEnrichment();
    initLogStatusRankSync();
    initCopyButtons();
    initFaviconFallbacks();
    initOptimisticStars();
    initInPlaceTriage();
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
