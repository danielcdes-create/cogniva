//! Read-only validation of private BSON exports. No database connection.
use crate::models::{
    activity::UserActivityDocument, question::QuestionDocument, user::UserProfileDocument,
};
use std::{
    fs::File,
    io::{BufReader, Seek},
    path::Path,
};

pub fn inspect(directory: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let mut result = serde_json::Map::new();
    for name in ["todasQuestoes", "userActivityLog", "usuarios"] {
        let file = File::open(directory.join(format!("{name}.bson")))?;
        let length = file.metadata()?.len();
        let mut reader = BufReader::new(file);
        let (mut total, mut valid, mut answerable) = (0, 0, 0);
        let mut errors = std::collections::BTreeMap::new();
        while reader.stream_position()? < length {
            let raw = bson::Document::from_reader(&mut reader)?;
            total += 1;
            let error = match name {
                "todasQuestoes" => match bson::from_document::<QuestionDocument>(raw) {
                    Ok(q) => {
                        valid += 1;
                        if q.answer_indices().is_ok() {
                            answerable += 1
                        }
                        None
                    }
                    Err(e) => Some(e.to_string()),
                },
                "userActivityLog" => match bson::from_document::<UserActivityDocument>(raw) {
                    Ok(_) => {
                        valid += 1;
                        None
                    }
                    Err(e) => Some(e.to_string()),
                },
                _ => match bson::from_document::<UserProfileDocument>(raw) {
                    Ok(_) => {
                        valid += 1;
                        None
                    }
                    Err(e) => Some(e.to_string()),
                },
            };
            if let Some(error) = error {
                *errors.entry(error).or_insert(0) += 1;
            }
        }
        result.insert(name.into(),serde_json::json!({"total":total,"typed_valid":valid,"answerable":answerable,"parse_errors":errors}));
    }
    println!("{}", serde_json::to_string_pretty(&result)?);
    Ok(())
}
