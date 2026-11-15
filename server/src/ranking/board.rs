/// Explicit tie-group bucket model for k-way ranking boards.
///
/// Storing tie groups directly as `Vec<Vec<String>>` (each inner `Vec` being a bucket of tied
/// entry IDs) eliminates index-relative `tied_with_above` array bugs when moving items into, out
/// of, or across existing tie groups.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RankBoard {
    buckets: Vec<Vec<String>>,
    selected_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RankBoardRow {
    pub entry_id: String,
    /// Zero-based visual row index.
    pub flat_index: usize,
    /// One-based standard competition (`1224`) rank.
    pub display_rank: usize,
    pub bucket_index: usize,
    pub index_in_bucket: usize,
    pub bucket_size: usize,
    pub is_tied_with_above: bool,
    pub is_selected: bool,
}

impl RankBoard {
    /// Creates a new board where each item starts in its own singleton bucket.
    #[must_use]
    pub fn new(ids: Vec<String>) -> Self {
        let selected_id = ids.first().cloned().unwrap_or_default();
        let buckets = ids.into_iter().map(|id| vec![id]).collect();
        Self { buckets, selected_id }
    }

    /// Creates a board from explicit tie buckets, dropping empty buckets.
    #[must_use]
    pub fn from_buckets(buckets: Vec<Vec<String>>) -> Self {
        let clean: Vec<Vec<String>> = buckets.into_iter().filter(|b| !b.is_empty()).collect();
        let selected_id = clean.first().and_then(|b| b.first()).cloned().unwrap_or_default();
        Self { buckets: clean, selected_id }
    }

    #[must_use]
    pub const fn is_empty(&self) -> bool {
        self.buckets.is_empty()
    }

    #[must_use]
    pub fn len(&self) -> usize {
        self.buckets.iter().map(Vec::len).sum()
    }

    #[must_use]
    pub fn selected_id(&self) -> &str {
        &self.selected_id
    }

    #[must_use]
    pub fn selected_flat_index(&self) -> usize {
        let mut idx = 0;
        for bucket in &self.buckets {
            for id in bucket {
                if id == &self.selected_id {
                    return idx;
                }
                idx += 1;
            }
        }
        0
    }

    #[must_use]
    pub fn is_all_tied(&self) -> bool {
        self.buckets.len() == 1 && self.len() >= 2
    }

    #[must_use]
    pub fn flat_rows(&self) -> Vec<RankBoardRow> {
        let mut rows = Vec::with_capacity(self.len());
        let mut flat_idx = 0usize;
        for (b_idx, bucket) in self.buckets.iter().enumerate() {
            let display_rank = flat_idx + 1;
            let b_len = bucket.len();
            for (i_idx, id) in bucket.iter().enumerate() {
                rows.push(RankBoardRow {
                    entry_id: id.clone(),
                    flat_index: flat_idx,
                    display_rank,
                    bucket_index: b_idx,
                    index_in_bucket: i_idx,
                    bucket_size: b_len,
                    is_tied_with_above: i_idx > 0,
                    is_selected: id == &self.selected_id,
                });
                flat_idx += 1;
            }
        }
        rows
    }

    #[must_use]
    pub fn to_ordering(&self) -> Vec<Vec<String>> {
        self.buckets.clone()
    }

    pub fn select_prev(&mut self) {
        let flat = self.flat_ids();
        if let Some(pos) = flat.iter().position(|id| id == &self.selected_id)
            && pos > 0
        {
            self.selected_id.clone_from(&flat[pos - 1]);
        }
    }

    pub fn select_next(&mut self) {
        let flat = self.flat_ids();
        if let Some(pos) = flat.iter().position(|id| id == &self.selected_id)
            && pos + 1 < flat.len()
        {
            self.selected_id.clone_from(&flat[pos + 1]);
        }
    }

    pub fn select_flat_index(&mut self, flat_index: usize) {
        let flat = self.flat_ids();
        if let Some(target) = flat.get(flat_index) {
            self.selected_id.clone_from(target);
        }
    }

    pub fn select_entry_id(&mut self, entry_id: &str) -> bool {
        if self.find_coords(entry_id).is_some() {
            entry_id.clone_into(&mut self.selected_id);
            true
        } else {
            false
        }
    }

    /// Moves the selected item one step higher without corrupting existing tie groups:
    /// - If inside a multi-item tie bucket, extracts ONLY the selected item into a singleton
    ///   bucket immediately above its former tie group (remaining members stay tied).
    /// - If already a singleton bucket at `b_idx > 0`, swaps that singleton bucket with the
    ///   bucket above it (preserving any multi-item tie in `b_idx - 1`).
    pub fn move_up(&mut self) {
        let Some((b_idx, i_idx)) = self.find_coords(&self.selected_id) else {
            return;
        };
        if self.buckets[b_idx].len() > 1 {
            let item = self.buckets[b_idx].remove(i_idx);
            self.buckets.insert(b_idx, vec![item]);
        } else if b_idx > 0 {
            self.buckets.swap(b_idx - 1, b_idx);
        }
    }

    /// Moves the selected item one step lower without corrupting existing tie groups:
    /// - If inside a multi-item tie bucket, extracts ONLY the selected item into a singleton
    ///   bucket immediately below its former tie group (remaining members stay tied).
    /// - If already a singleton bucket with a bucket below it, swaps the two buckets
    ///   (preserving any multi-item tie in `b_idx + 1`).
    pub fn move_down(&mut self) {
        let Some((b_idx, i_idx)) = self.find_coords(&self.selected_id) else {
            return;
        };
        if self.buckets[b_idx].len() > 1 {
            let item = self.buckets[b_idx].remove(i_idx);
            self.buckets.insert(b_idx + 1, vec![item]);
        } else if b_idx + 1 < self.buckets.len() {
            self.buckets.swap(b_idx, b_idx + 1);
        }
    }

    /// Moves the selected item to `target_zero_based` flat rank slot as an untied singleton
    /// without splitting any existing multi-item tie buckets.
    pub fn move_to_rank_slot(&mut self, target_zero_based: usize) {
        let total = self.len();
        if total <= 1 {
            return;
        }
        let target = target_zero_based.min(total - 1);
        let old_flat = self.selected_flat_index();
        let Some((b_idx, i_idx)) = self.find_coords(&self.selected_id) else {
            return;
        };

        let item = self.buckets[b_idx].remove(i_idx);
        if self.buckets[b_idx].is_empty() {
            self.buckets.remove(b_idx);
        }

        let mut acc = 0usize;
        let mut insert_bucket_idx = self.buckets.len();
        for (idx, bucket) in self.buckets.iter().enumerate() {
            if target == acc {
                insert_bucket_idx = idx;
                break;
            }
            let next_acc = acc + bucket.len();
            if target < next_acc {
                // `target` falls inside a multi-item tie bucket; place immediately after when
                // moving downward and immediately before when moving upward so the tie stays intact.
                insert_bucket_idx = if old_flat <= target { idx + 1 } else { idx };
                break;
            }
            acc = next_acc;
        }
        self.buckets.insert(insert_bucket_idx, vec![item]);
    }

    /// Toggles the tie boundary immediately above the selected item (`=` key / button).
    /// No-op when the selected item is at flat index `0`.
    pub fn toggle_tie_with_above(&mut self) {
        let Some((b_idx, i_idx)) = self.find_coords(&self.selected_id) else {
            return;
        };
        if i_idx > 0 {
            let tail = self.buckets[b_idx].split_off(i_idx);
            self.buckets.insert(b_idx + 1, tail);
        } else if b_idx > 0 {
            let mut cur = self.buckets.remove(b_idx);
            self.buckets[b_idx - 1].append(&mut cur);
        }
    }

    /// Toggles tying all items into a single bucket vs splitting all items into singletons.
    pub fn toggle_tie_all(&mut self) {
        if self.len() <= 1 {
            return;
        }
        let flat = self.flat_ids();
        if self.buckets.len() == 1 {
            self.buckets = flat.into_iter().map(|id| vec![id]).collect();
        } else {
            self.buckets = vec![flat];
        }
    }

    fn flat_ids(&self) -> Vec<String> {
        self.buckets.iter().flat_map(|b| b.iter().cloned()).collect()
    }

    fn find_coords(&self, target_id: &str) -> Option<(usize, usize)> {
        for (b_idx, bucket) in self.buckets.iter().enumerate() {
            if let Some(i_idx) = bucket.iter().position(|id| id == target_id) {
                return Some((b_idx, i_idx));
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(x: &str) -> String {
        x.to_owned()
    }

    #[test]
    fn test_move_up_and_down_preserve_existing_tie_groups() {
        let mut board = RankBoard::from_buckets(vec![vec![s("a"), s("b")], vec![s("c")]]);
        assert!(board.select_entry_id("c"));

        // Moving singleton "c" above tie group ["a", "b"] keeps "a" and "b" tied at #2!
        board.move_up();
        assert_eq!(board.to_ordering(), vec![vec![s("c")], vec![s("a"), s("b")]]);
        let rows = board.flat_rows();
        assert_eq!(rows[0].display_rank, 1);
        assert_eq!(rows[1].display_rank, 2);
        assert_eq!(rows[2].display_rank, 2);
        assert!(rows[2].is_tied_with_above);

        // Moving "c" back down restores [["a", "b"], ["c"]].
        board.move_down();
        assert_eq!(board.to_ordering(), vec![vec![s("a"), s("b")], vec![s("c")]]);
    }

    #[test]
    fn test_extracting_member_from_three_way_tie_keeps_others_tied() {
        let mut board = RankBoard::from_buckets(vec![vec![s("a"), s("b"), s("c")]]);
        assert!(board.select_entry_id("b"));

        board.move_up();
        assert_eq!(board.to_ordering(), vec![vec![s("b")], vec![s("a"), s("c")]]);

        // Re-tying ["a", "c"] onto "b" via "=" on "a":
        assert!(board.select_entry_id("a"));
        board.toggle_tie_with_above();
        assert_eq!(board.to_ordering(), vec![vec![s("b"), s("a"), s("c")]]);
    }

    #[test]
    fn test_pairwise_tie_toggle_is_exact_inverse() {
        let mut board = RankBoard::new(vec![s("a"), s("b"), s("c")]);
        assert!(board.select_entry_id("b"));
        board.toggle_tie_with_above();
        assert_eq!(board.to_ordering(), vec![vec![s("a"), s("b")], vec![s("c")]]);

        board.toggle_tie_with_above();
        assert_eq!(board.to_ordering(), vec![vec![s("a")], vec![s("b")], vec![s("c")]]);
    }

    #[test]
    fn test_move_to_rank_slot_does_not_tear_tie_buckets() {
        let mut board =
            RankBoard::from_buckets(vec![vec![s("a")], vec![s("b"), s("c")], vec![s("d")]]);
        assert!(board.select_entry_id("d"));

        // Press '2' (slot 1): "d" moves to #2 above ["b", "c"], keeping ["b", "c"] tied at #3.
        board.move_to_rank_slot(1);
        assert_eq!(board.to_ordering(), vec![vec![s("a")], vec![s("d")], vec![s("b"), s("c")]]);
    }

    #[test]
    fn test_toggle_tie_all_roundtrip() {
        let mut board = RankBoard::new(vec![s("a"), s("b"), s("c")]);
        board.toggle_tie_all();
        assert!(board.is_all_tied());
        assert_eq!(board.to_ordering(), vec![vec![s("a"), s("b"), s("c")]]);

        board.toggle_tie_all();
        assert!(!board.is_all_tied());
        assert_eq!(board.to_ordering(), vec![vec![s("a")], vec![s("b")], vec![s("c")]]);
    }
}
