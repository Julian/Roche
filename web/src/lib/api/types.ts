// Shared API types matching Roche Rust server `/api/v1/*` JSON schemas.

export type EntryKind =
  | 'article'
  | 'podcast_episode'
  | 'video'
  | 'paper'
  | 'book'
  | 'movie'
  | 'tv'
  | 'album'
  | 'song'
  | 'artist'
  | 'podcast'
  | 'publication'
  | 'channel'
  | 'game'
  | 'collection';

export type ContentType = 'article' | 'audio' | 'video' | 'paper' | 'pdf';
export type MediaType = 'audio' | 'video' | 'pdf' | 'thumbnail';
export type FeedType = 'rss' | 'podcast' | 'youtube' | 'arxiv';
export type ItemStatus = 'unread' | 'archived';
export type EntryTriageState = 'new' | 'finished' | 'dismissed';
export type ConsumptionStatus = 'consumed' | 'started' | 'abandoned';
export type CollectionRole = 'list' | 'want' | 'system' | 'feed' | 'tag';
export type BoardType = 'hacker_news';
export type BoardOrderMode = 'ranked' | 'chronological';

export type WebLinkIcon =
  | 'wikipedia'
  | 'spotify'
  | 'film'
  | 'book'
  | 'paper'
  | 'game'
  | 'rss'
  | 'discussion'
  | 'external';

export interface WebExternalLink {
  label: string;
  href: string;
  icon: WebLinkIcon;
}

export interface DiscussionLink {
  source: string;
  url: string;
  label?: string | null;
}

export interface EntryIdentifier {
  scheme: string;
  value: string;
}

export interface SessionStatus {
  authenticated: boolean;
  csrf_token: string | null;
  tailscale_enabled: boolean;
  tailscale_user: string | null;
  is_refreshing: boolean;
  active_refresh_count: number;
  unread_items_count: number;
}

export interface SyncStatus {
  status: string;
  feeds_count: number;
  unread_items_count: number;
  archived_items_count: number;
  latest_activity_id: number;
  is_refreshing: boolean;
  active_refresh_count: number;
  timestamp: string;
}

export interface Tag {
  id: string;
  name: string;
  color: string | null;
  created_at: string;
}

export interface MediaAsset {
  id: string;
  item_id: string;
  media_type: MediaType;
  file_size: number | null;
  duration: number | null;
}

export interface Feed {
  id: string;
  feed_type: FeedType;
  title: string | null;
  url: string;
  description: string | null;
  poll_interval: number;
  is_active: boolean;
  last_polled: string | null;
  last_error: string | null;
  unread_count: number;
  created_at: string;
}

export interface Item {
  id: string;
  entry_kind?: EntryKind | null;
  feed_id: string | null;
  feed_url: string | null;
  feed_title: string | null;
  url: string;
  title: string;
  author: string | null;
  summary: string | null;
  content_html: string | null;
  content_type: ContentType;
  published_at: string | null;
  thumbnail_url: string | null;
  audio_url: string | null;
  duration: number | null;
  status: ItemStatus;
  is_favorite: boolean;
  is_finished: boolean;
  in_want_to_read: boolean;
  read_progress: number;
  read_at: string | null;
  tag_names: string[];
  discussions: DiscussionLink[];
  created_at: string;
}

export interface ItemDetail extends Item {
  tags: Tag[];
  media: MediaAsset[];
}

export interface ItemPage {
  items: Item[];
  page: number;
  per_page: number;
  has_more: boolean;
}

export interface InboxPagePayload extends ItemPage {
  title_html: Record<string, string>;
}

export interface CatalogEntry {
  id: string;
  kind: EntryKind;
  title: string;
  creators: string[];
  url: string | null;
  published_at: string | null;
  release_year: number | null;
  summary: string | null;
  thumbnail_url: string | null;
  metadata: Record<string, unknown>;
  identifiers: EntryIdentifier[];
  created_at: string;
  updated_at: string;
}

export interface ConsumptionLogRow {
  id: string;
  entry_id: string;
  status: ConsumptionStatus;
  ts: string;
  notes: string | null;
  created_at: string;
}

export interface ConsumptionLogView {
  id: string;
  entry: CatalogEntry;
  status: ConsumptionStatus;
  notes: string | null;
  ts: string;
}

export interface CollectionSummary {
  id: string;
  slug: string | null;
  title: string;
  summary: string | null;
  role: CollectionRole;
  rule_json: string | null;
  member_count: number;
  comparison_count: number;
  has_generator: boolean;
}

export interface CollectionGeneratorState {
  collection_id: string;
  collection_slug: string | null;
  collection_title: string;
  enabled: boolean;
  current_entry: CatalogEntry | null;
  drawn_at: string | null;
  updated_at: string;
  total_pool: number;
  consumed_count: number;
  abandoned_count: number;
  remaining_count: number;
}

export interface RankingScore {
  mean: number;
  variance: number;
}

export interface RankedEntryRow {
  rank: number;
  entry: CatalogEntry;
  score: RankingScore;
  tier: 'S' | 'A' | 'B' | 'C' | 'D' | null;
  bar_pct: number;
}

export interface RankedCollectionView {
  collection: CollectionSummary;
  is_priority_scope: boolean;
  comparison_count: number;
  rows: RankedEntryRow[];
}

export interface EntryDetailView {
  entry: CatalogEntry;
  children: CatalogEntry[];
  parents: CatalogEntry[];
  log_history: ConsumptionLogRow[];
  total_log_count: number;
  has_consumed_log: boolean;
}

export interface EntryListAppearance {
  slug: string;
  title: string;
  rank: number;
  total: number;
  tier: 'S' | 'A' | 'B' | 'C' | 'D' | null;
}

export interface AlbumTrack {
  number: number;
  disc: number;
  title: string;
  duration_ms: number | null;
  artists: string[];
  spotify_uri: string | null;
  entry_id: string | null;
}

export interface DiscographyAlbum {
  title: string;
  release_year: number | null;
  thumbnail_url: string | null;
  apple_music_id: string | null;
}

export interface EntryParentListLink {
  id: string;
  slug: string;
  title: string;
  has_generator: boolean;
  is_editable_manual: boolean;
}

export interface Board {
  id: string;
  adapter_type: BoardType;
  source_key: string;
  source_url: string;
  source_config_json: string;
  title: string;
  order_mode: BoardOrderMode;
  poll_interval: number;
  is_active: boolean;
  last_polled_at: string | null;
  last_error: string | null;
  snapshot_fetched_at: string | null;
  item_count: number;
  visible_count: number;
  created_at: string;
  updated_at: string;
}

export interface BoardItemView {
  board_id: string;
  external_id: string;
  rank: number;
  title: string;
  url: string;
  discussion_url: string | null;
  author: string | null;
  domain: string | null;
  published_at: string | null;
  score: number | null;
  comment_count: number | null;
  summary: string | null;
  self_text_html: string | null;
  fetched_at: string;
  seen_at: string | null;
  dismissed_at: string | null;
  is_seen: boolean;
  is_dismissed: boolean;
  linked_entry_id: string | null;
  is_favorite: boolean;
  in_want_to_read: boolean;
  is_finished: boolean;
}

export interface BoardItemsPage {
  board: Board;
  items: BoardItemView[];
  page: number;
  per_page: number;
  total_buffered: number;
  has_more_buffered: boolean;
  has_more_live: boolean;
  is_live_page: boolean;
  next_live_offset: number | null;
}

export type BoardReaderFallbackReason =
  | 'self_post_empty'
  | 'pdf'
  | 'video'
  | 'paper'
  | 'fetch_failed'
  | 'unextractable';

export interface BoardReaderView {
  board: Board;
  item: BoardItemView;
  content_html: string | null;
  extracted_summary: string | null;
  thumbnail_url: string | null;
  is_self_post: boolean;
  from_cache: boolean;
  fallback_reason?: BoardReaderFallbackReason | null;
  linked_entry: Item | null;
}

export interface BoardReaderRichPayload {
  view: BoardReaderView;
  title_html: string;
  content_html: string | null;
  fallback_summary_html: string | null;
  reading_minutes: number | null;
  tags: Tag[];
  can_rank: boolean;
  rank_slug: string | null;
}

export interface BoardDismissViewResponse {
  outcome: {
    board_id: string;
    external_id: string;
    dismissed_at: string;
    replacement_item: BoardItemView | null;
  };
  visible_count: number;
  dismissed_count: number;
}

export interface BoardUndismissViewResponse {
  state: {
    board_id: string;
    external_id: string;
    seen_at: string | null;
    dismissed_at: string | null;
  };
  visible_count: number;
  dismissed_count: number;
}

export interface BoardPromoteViewResponse {
  result: {
    entry_id: string;
    created_new: boolean;
    item: Item;
    board_item: BoardItemView | null;
  };
  rank_slug: string | null;
}

export interface HomePayload {
  in_progress: Item[];
  unread_items: Item[];
  unread_total: number;
  want_to_read_items: Item[];
  want_to_read_total: number;
  other_want_total: number;
  active_generators: CollectionGeneratorState[];
  generator_external_links: Record<string, WebExternalLink[]>;
  generator_tags: Record<string, string[]>;
  recent_diary: ConsumptionLogView[];
  artist_links: Record<string, string>;
  diary_tiers: Record<string, 'S' | 'A' | 'B' | 'C' | 'D'>;
  title_html: Record<string, string>;
  is_refreshing: boolean;
}

export interface WorkspaceSearchResults {
  query: string;
  catalog_entries: CatalogEntry[];
  collections: CollectionSummary[];
  want_entries: Array<{
    queue_id: string;
    queue_slug: string | null;
    queue_title: string;
    entry: CatalogEntry;
  }>;
  inbox_items: Item[];
  diary_logs: ConsumptionLogView[];
}

export interface RichSearchPayload {
  results: WorkspaceSearchResults;
  artist_links: Record<string, string>;
  reader_item_ids: string[];
  title_html: Record<string, string>;
}

export interface ReaderDiscussionLink {
  label: string;
  url: string;
}

export interface ItemReaderPayload {
  detail: ItemDetail;
  feed: Feed | null;
  title_html: string;
  summary_html: string | null;
  content_html: string | null;
  reading_minutes: number | null;
  triage_state: EntryTriageState;
  is_consumed: boolean;
  can_rank: boolean;
  rank_slug: string | null;
  entry_release_year: number | null;
  entry_arxiv_id: string | null;
  pdf_media_id: string | null;
  video_media_id: string | null;
  audio_media_id: string | null;
  pdf_mode: 'extracted' | 'pdf' | 'split' | null;
  pdf_unextracted: boolean;
  discussions: ReaderDiscussionLink[];
}

export interface FinishItemResponse {
  item: ItemDetail;
  has_feed: boolean;
  rank_slug: string | null;
}

export interface ListsOverviewPayload {
  collections: CollectionSummary[];
  dynamic_collection_ids: string[];
  previews: Record<string, Array<[string, string]>>;
  generators: Record<string, CollectionGeneratorState>;
  generator_external_links: Record<string, WebExternalLink[]>;
  search_entries: CatalogEntry[] | null;
  search_truncated: boolean;
  invalid_kind: string | null;
  artist_links: Record<string, string>;
  title_html: Record<string, string>;
}

export interface ListDetailPayload {
  view: RankedCollectionView;
  is_dynamic: boolean;
  total_matching: number;
  full_total: number;
  full_tier_counts: [number, number, number, number, number];
  kind_counts: Array<[EntryKind, number]>;
  page_num: number;
  total_pages: number;
  reader_item_ids: string[];
  artist_links: Record<string, string>;
  generator: CollectionGeneratorState | null;
  generator_external_links: WebExternalLink[];
  generator_tags: string[];
  title_html: Record<string, string>;
}

export interface EntryDetailRichPayload {
  detail: EntryDetailView;
  needs_enrich: boolean;
  can_refresh: boolean;
  appearances: EntryListAppearance[];
  child_tiers: Record<string, 'S' | 'A' | 'B' | 'C' | 'D'>;
  has_reader_item: boolean;
  artist_links: Record<string, string>;
  external_links: WebExternalLink[];
  tags: string[];
  tracks: AlbumTrack[];
  external_discography: DiscographyAlbum[];
  want_queue_slug: string | null;
  parent_lists: EntryParentListLink[];
  available_manual_lists: CollectionSummary[];
  title_html: string;
  summary_html: string | null;
  child_title_html: Record<string, string>;
  merge_preview: EntryDetailView | null;
  merge_preview_error: string | null;
}

export interface DiaryPagePayload {
  entries: ConsumptionLogView[];
  total_for_filter: number;
  total_all_years: number;
  page_num: number;
  total_pages: number;
  year_counts: Array<[number, number]>;
  non_song_kind_counts: Array<[EntryKind, number]>;
  has_songs: boolean;
  cadence_year: number | null;
  cadence_months: Array<[string, number]>;
  artist_links: Record<string, string>;
  diary_tiers: Record<string, 'S' | 'A' | 'B' | 'C' | 'D'>;
  title_html: Record<string, string>;
}

export interface QueuesPayload {
  queues: RankedCollectionView[];
  total_items: number;
  artist_links: Record<string, string>;
  reader_item_ids: string[];
  title_html: Record<string, string>;
}

export interface QuickLogResponse {
  action: string;
  entry: CatalogEntry;
  queue_title: string | null;
  rank_slug: string | null;
  rank_want: boolean;
}

export interface RankSessionPayload {
  collection: CollectionSummary;
  want: boolean;
  is_priority_scope: boolean;
  comparison_count: number;
  total_rows: number;
  round: number;
  skip: number;
  max_rounds: number;
  effective_k: number;
  focus_id: string | null;
  focus_row: RankedEntryRow | null;
  cluster: CatalogEntry[];
  priority_ids: string[];
  artist_links: Record<string, string>;
  open_urls: Record<string, string>;
  title_html: Record<string, string>;
  settled: boolean;
  focus_missing: boolean;
}

export interface RankSubmitResponse {
  comparison: {
    id: string;
    scope_collection_id: string | null;
    ordering: string[][];
    ts: string;
  };
  next_covered: string;
  next_excluded: string;
  undo_excluded: string | null;
  want: boolean;
}

export const WANT_TO_READ_COLLECTION_ID = '2ef71101-a716-5331-9515-cb7b5ce8b3c3';
export const WANT_TO_READ_SLUG = 'want:read';
