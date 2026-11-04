package com.grayvines.roche.data.local

object SeedData {
    private const val FEED_ARXIV_LG = "feed-arxiv-cs-lg"
    private const val FEED_LWN = "feed-lwn"
    private const val FEED_OXIDE = "feed-oxide"
    private const val FEED_DANLUU = "feed-danluu"
    private const val FEED_ARXIV_SYS = "feed-arxiv-sys"

    private const val TITLE_ARXIV_LG = "arXiv cs.LG"
    private const val TITLE_LWN = "LWN.net"
    private const val TITLE_OXIDE = "Oxide and Friends"
    private const val TITLE_DANLUU = "Dan Luu"
    private const val TITLE_ARXIV_SYS = "arXiv cs.DC"

    private const val TYPE_RSS = "rss"
    private const val TYPE_ARXIV = "arxiv"
    private const val TYPE_ARTICLE = "article"
    private const val TYPE_PDF = "pdf"
    private const val TYPE_VIDEO = "video"

    private const val STATUS_UNREAD = "unread"
    private const val STATUS_ARCHIVED = "archived"

    private const val TAG_LLM = "tag-llm-inference"
    private const val TAG_SYSTEMS = "tag-systems"
    private const val TAG_STORAGE = "tag-storage"
    private const val TAG_SQLITE = "tag-sqlite"
    private const val TAG_COMPILERS = "tag-compilers"

    private const val ITEM_1 = "item-1"
    private const val ITEM_2 = "item-2"
    private const val ITEM_3 = "item-3"
    private const val ITEM_4 = "item-4"
    private const val ITEM_5 = "item-5"
    private const val ITEM_6 = "item-6"

    val feeds: List<FeedEntity> =
        listOf(
            FeedEntity(
                id = FEED_ARXIV_LG,
                title = TITLE_ARXIV_LG,
                url = "https://export.arxiv.org/api/query?search_query=cat:cs.LG",
                siteUrl = "https://arxiv.org/list/cs.LG/recent",
                feedType = TYPE_ARXIV,
            ),
            FeedEntity(
                id = FEED_LWN,
                title = TITLE_LWN,
                url = "https://lwn.net/headlines/rss",
                siteUrl = "https://lwn.net",
                feedType = TYPE_RSS,
            ),
            FeedEntity(
                id = FEED_OXIDE,
                title = TITLE_OXIDE,
                url =
                    "https://www.youtube.com/feeds/videos.xml?channel_id=UCfe_znKY1ukrqlGActlFmaQ",
                siteUrl = "https://oxide.computer",
                feedType = "youtube",
            ),
            FeedEntity(
                id = FEED_DANLUU,
                title = TITLE_DANLUU,
                url = "https://danluu.com/atom.xml",
                siteUrl = "https://danluu.com",
                feedType = TYPE_RSS,
            ),
            FeedEntity(
                id = FEED_ARXIV_SYS,
                title = TITLE_ARXIV_SYS,
                url = "https://export.arxiv.org/api/query?search_query=cat:cs.DC",
                siteUrl = "https://arxiv.org/list/cs.DC/recent",
                feedType = TYPE_ARXIV,
            ),
        )

    val tags: List<TagEntity> =
        listOf(
            TagEntity(id = TAG_LLM, name = "llm-inference"),
            TagEntity(id = TAG_SYSTEMS, name = "systems"),
            TagEntity(id = TAG_STORAGE, name = "storage"),
            TagEntity(id = TAG_SQLITE, name = "sqlite"),
            TagEntity(id = TAG_COMPILERS, name = "compilers"),
        )

    val items: List<ItemEntity> =
        listOf(
            ItemEntity(
                id = ITEM_1,
                feedId = FEED_ARXIV_LG,
                feedTitle = TITLE_ARXIV_LG,
                title = "Speculative Decoding with Quantized Draft Models on Edge NPU Targets",
                url = "https://arxiv.org/abs/2603.11842",
                author = "Chen, Alvarez, K. Sato",
                summary =
                    "Wait-free verification schedules reduce token latency by 2.4x under strict 6 GB DRAM envelopes without accuracy loss.",
                contentText =
                    "Autoregressive decoding from large language models on mobile and embedded NPUs remains bottlenecked by memory bandwidth rather than arithmetic throughput. While speculative decoding amortizes target-model weight reads across multiple candidate tokens, existing pipelines suffer from KV-cache fragmentation and verification stalls when draft and target models contend for unified LPDDR5X channels.\n\nWe introduce a deterministic ring-buffer KV allocator paired with an asymmetric W4A8 draft schedule that keeps the speculative tree resident inside on-chip SRAM tiles. Across Llama-3-8B and Qwen-2.5-7B variants, our schedule achieves 2.4x end-to-end speedup with zero degradation in target distribution fidelity.",
                contentHtml = null,
                contentType = TYPE_PDF,
                status = STATUS_UNREAD,
                isFavorite = true,
                isCachedOffline = true,
                publishedAt = "18m",
                createdAt = "2026-03-29T10:00:00Z",
                readProgress = 0.35,
                thumbnailMediaId = "media-paper-1",
                duration = "14p",
            ),
            ItemEntity(
                id = ITEM_2,
                feedId = FEED_LWN,
                feedTitle = TITLE_LWN,
                title =
                    "SQLite in WAL2 mode: Measuring write amplification on modern NVMe namespaces",
                url = "https://lwn.net/Articles/984120/",
                author = "Jonathan Corbet",
                summary =
                    "Benchmarking concurrent readers during checkpoint turnovers when paired with ZNS and conventional FTL devices.",
                contentText =
                    "Write-Ahead Logging (WAL) in SQLite has long faced a fundamental pathology under sustained write workloads: if a long-lived reader holds a snapshot on the WAL file, checkpoints cannot reset the log to byte zero, causing unbounded WAL growth until all readers advance.\n\nWAL2 addresses this by alternating between two bounded WAL files (wal1 and wal2). Once the active WAL crosses a configurable frame threshold, writers seamlessly pivot to the secondary WAL file while existing readers drain from the primary file, bounding tail latency and write amplification on modern NVMe devices.",
                contentHtml = null,
                contentType = TYPE_ARTICLE,
                status = STATUS_UNREAD,
                isFavorite = false,
                isCachedOffline = true,
                publishedAt = "2h",
                createdAt = "2026-03-29T08:00:00Z",
                readProgress = 0.0,
                thumbnailMediaId = null,
                duration = null,
            ),
            ItemEntity(
                id = ITEM_3,
                feedId = FEED_OXIDE,
                feedTitle = TITLE_OXIDE,
                title = "Postmortem: Debugging a silent PCIe link training regression at 32 GT/s",
                url = "https://www.youtube.com/watch?v=oxide-pcie-debug",
                author = "Bryan Cantrill & Robert Mustacchi",
                summary =
                    "Tracing equalization preset mismatches across retimers with an oscilloscope and custom host firmware traps.",
                contentText =
                    "Transcript excerpt: At 32 GT/s (PCIe Gen 5), signal integrity margins shrink to the point where a single suboptimal transmitter equalization preset on a downstream retimer can trigger intermittent recovery loops under thermal load.\n\nIn this session we walk through the exact state-machine trace captured from the root complex LTSSM registers, why link training appeared to succeed at boot, and how we isolated the lane-to-lane skew anomaly.",
                contentHtml = null,
                contentType = TYPE_VIDEO,
                status = STATUS_UNREAD,
                isFavorite = true,
                isCachedOffline = true,
                publishedAt = "5h",
                createdAt = "2026-03-29T05:00:00Z",
                readProgress = 0.0,
                thumbnailMediaId = "media-video-1",
                duration = "28:14",
            ),
            ItemEntity(
                id = ITEM_4,
                feedId = FEED_DANLUU,
                feedTitle = TITLE_DANLUU,
                title = "Why local-first sync engines still struggle with schema migrations",
                url = "https://danluu.com/schema-sync/",
                author = TITLE_DANLUU,
                summary =
                    "Comparing state-based CRDTs, event logs, and deterministic server-authoritative cursors for single-user tools.",
                contentText =
                    "When you only have one human user across a phone, a laptop, and a self-hosted server, full peer-to-peer multi-writer CRDTs often introduce more accidental complexity than they solve—especially once you need a relational schema migration.\n\nA server-authoritative SQLite log with idempotent client mutations and optimistic local projection gives you 99% of the offline UX with a fraction of the state-explosion edge cases.",
                contentHtml = null,
                contentType = TYPE_ARTICLE,
                status = STATUS_UNREAD,
                isFavorite = false,
                isCachedOffline = false,
                publishedAt = "1d",
                createdAt = "2026-03-28T10:00:00Z",
                readProgress = 0.0,
                thumbnailMediaId = null,
                duration = null,
            ),
            ItemEntity(
                id = ITEM_5,
                feedId = FEED_ARXIV_SYS,
                feedTitle = TITLE_ARXIV_SYS,
                title = "Deterministic Simulation Testing for Embedded Storage Engines in Rust",
                url = "https://arxiv.org/abs/2603.09910",
                author = "M. Lindström, A. Verma",
                summary =
                    "Fault-injected NVMe flush reordering uncovers three subtle torn-page recovery bugs in under four CPU hours.",
                contentText =
                    "Storage engines rely on strict ordering guarantees around fdatasync and NVMe cache flushes, yet hardware controllers routinely reorder unbarriered writes before a power loss event.\n\nBy abstracting block device I/O behind a deterministic seed-driven simulator in Rust, we explore millions of crash-recovery permutations per hour on a single workstation.",
                contentHtml = null,
                contentType = TYPE_PDF,
                status = STATUS_UNREAD,
                isFavorite = false,
                isCachedOffline = false,
                publishedAt = "2d",
                createdAt = "2026-03-27T10:00:00Z",
                readProgress = 0.0,
                thumbnailMediaId = "media-paper-2",
                duration = "12p",
            ),
            ItemEntity(
                id = ITEM_6,
                feedId = FEED_LWN,
                feedTitle = TITLE_LWN,
                title = "Zero-copy networking with io_uring multishot recv and registered buffers",
                url = "https://lwn.net/Articles/983001/",
                author = "Daroc Alden",
                summary =
                    "How buffer rings eliminate per-packet syscall and page-table overhead in high-throughput proxies.",
                contentText =
                    "Registered buffer rings allow user space to hand a pool of pre-mapped pages to the kernel ahead of time. Combined with multishot receive requests, a single submission queue entry can service thousands of incoming packets without re-arming.",
                contentHtml = null,
                contentType = TYPE_ARTICLE,
                status = STATUS_ARCHIVED,
                isFavorite = false,
                isCachedOffline = false,
                publishedAt = "4d",
                createdAt = "2026-03-25T10:00:00Z",
                readProgress = 1.0,
                thumbnailMediaId = null,
                duration = null,
            ),
        )

    val itemTags: List<ItemTagEntity> =
        listOf(
            ItemTagEntity(itemId = ITEM_1, tagId = TAG_LLM),
            ItemTagEntity(itemId = ITEM_1, tagId = TAG_SYSTEMS),
            ItemTagEntity(itemId = ITEM_2, tagId = TAG_SQLITE),
            ItemTagEntity(itemId = ITEM_2, tagId = TAG_STORAGE),
            ItemTagEntity(itemId = ITEM_3, tagId = TAG_SYSTEMS),
            ItemTagEntity(itemId = ITEM_4, tagId = TAG_SQLITE),
            ItemTagEntity(itemId = ITEM_5, tagId = TAG_STORAGE),
            ItemTagEntity(itemId = ITEM_5, tagId = TAG_SYSTEMS),
        )
}
