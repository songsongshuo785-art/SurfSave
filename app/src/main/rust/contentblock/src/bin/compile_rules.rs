use std::env;
use std::fs;
use std::path::PathBuf;
use surfsave_content_block::compile_engine_bytes;

fn main() -> Result<(), String> {
    let mut args = env::args_os().skip(1);
    let output = args
        .next()
        .map(PathBuf::from)
        .ok_or_else(|| "missing output path".to_string())?;
    let context_free = args
        .next()
        .and_then(|value| value.to_str().map(|value| value == "context-free"))
        .ok_or_else(|| "missing mode (full or context-free)".to_string())?;
    let inputs = args.map(PathBuf::from).collect::<Vec<_>>();
    if inputs.is_empty() {
        return Err("at least one filter list is required".to_string());
    }

    let lists = inputs
        .iter()
        .map(|path| {
            fs::read_to_string(path)
                .map_err(|error| format!("failed to read {}: {error}", path.display()))
        })
        .collect::<Result<Vec<_>, _>>()?;
    let bytes = compile_engine_bytes(lists, context_free)?;
    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("failed to create {}: {error}", parent.display()))?;
    }
    fs::write(&output, &bytes)
        .map_err(|error| format!("failed to write {}: {error}", output.display()))?;
    println!("{}|{}", output.display(), bytes.len());
    Ok(())
}
