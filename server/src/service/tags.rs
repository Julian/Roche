use super::{AppError, RocheService};
use crate::{db::Tag, domain::HexColor};

pub struct TagService<'a> {
    pub(super) service: &'a RocheService,
}

impl TagService<'_> {
    pub async fn list(&self) -> Result<Vec<Tag>, AppError> {
        Ok(self.service.db.tags().list().await?)
    }

    pub async fn list_id_name_pairs(&self) -> Result<Vec<(String, String)>, AppError> {
        Ok(self.service.db.tags().list_id_name_pairs().await?)
    }

    pub async fn get(&self, id: &str) -> Result<Tag, AppError> {
        self.service.db.tags().get(id).await?.ok_or_else(|| AppError::not_found("Tag not found"))
    }

    pub async fn create(&self, name: &str, color: Option<&HexColor>) -> Result<Tag, AppError> {
        let trimmed = name.trim();
        if trimmed.is_empty() {
            return Err(AppError::bad_request("Tag name cannot be empty"));
        }
        Ok(self.service.db.tags().create(trimmed, color).await?)
    }

    pub async fn update(
        &self,
        id: &str,
        name: Option<&str>,
        color: Option<&HexColor>,
    ) -> Result<Tag, AppError> {
        let clean_name = match name {
            Some(n) => {
                let trimmed = n.trim();
                if trimmed.is_empty() {
                    return Err(AppError::bad_request("Tag name cannot be empty"));
                }
                Some(trimmed)
            }
            None => None,
        };

        if !self.service.db.tags().update(id, clean_name, color).await? {
            return Err(AppError::not_found("Tag not found"));
        }
        self.get(id).await
    }

    pub async fn delete(&self, id: &str) -> Result<(), AppError> {
        let repo = self.service.db.tags();
        if !repo.delete(id).await? {
            return Err(AppError::not_found("Tag not found"));
        }
        Ok(())
    }
}
