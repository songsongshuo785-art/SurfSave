use adblock::lists::ParseOptions;
use adblock::request::Request;
use adblock::{Engine, FilterSet};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jobjectArray, jstring};
use serde::Serialize;
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr::null_mut;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, OnceLock, RwLock};

const NATIVE_VERSION: &str = "surfsave-content-block/0.1.0 adblock-rust/0.13.3";
const DECISION_ALLOW: jint = 0;
const DECISION_BLOCK: jint = 1;
const DECISION_INVALID_REQUEST: jint = 2;
const DECISION_ENGINE_MISSING: jint = 3;
const MAX_LISTS: usize = 16;
const MAX_LIST_BYTES: usize = 16 * 1024 * 1024;
const MAX_ARRAY_ITEMS: usize = 4_096;
const MAX_ARRAY_ITEM_BYTES: usize = 1_024;
const MAX_PROCEDURAL_ACTIONS: usize = 256;
const MAX_PROCEDURAL_ACTION_BYTES: usize = 8 * 1_024;
const MAX_PROCEDURAL_PAYLOAD_BYTES: usize = 256 * 1_024;

#[derive(Default, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct RuleBuildStats {
    accepted_lines: u64,
    ignored_lines: u64,
    unsupported_redirect: u64,
    unsupported_remove_param: u64,
    unsupported_csp: u64,
    rejected_remote_scriptlet: u64,
    context_dependent_service_worker: u64,
    serialized_bytes: usize,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CosmeticPayload {
    hide_selectors: Vec<String>,
    exceptions: Vec<String>,
    generichide: bool,
    procedural_actions: Vec<Value>,
    procedural_ignored: usize,
    scriptlets_ignored: bool,
}

fn cosmetic_payload(engine: &Engine, url: &str) -> CosmeticPayload {
    let resources = engine.url_cosmetic_resources(url);
    let procedural_total = resources.procedural_actions.len();
    let mut hide_selectors = resources.hide_selectors.into_iter().collect::<Vec<_>>();
    let mut exceptions = resources.exceptions.into_iter().collect::<Vec<_>>();
    let mut procedural_json = resources.procedural_actions.into_iter().collect::<Vec<_>>();
    hide_selectors.sort_unstable();
    exceptions.sort_unstable();
    procedural_json.sort_unstable();

    let mut procedural_bytes = 0usize;
    let procedural_actions = procedural_json
        .into_iter()
        .filter_map(|encoded| {
            if encoded.len() > MAX_PROCEDURAL_ACTION_BYTES ||
                procedural_bytes.saturating_add(encoded.len()) > MAX_PROCEDURAL_PAYLOAD_BYTES
            {
                return None;
            }
            let value = serde_json::from_str::<Value>(&encoded).ok()?;
            if !value.is_object() {
                return None;
            }
            procedural_bytes += encoded.len();
            Some(value)
        })
        .take(MAX_PROCEDURAL_ACTIONS)
        .collect::<Vec<_>>();
    let procedural_ignored = procedural_total.saturating_sub(procedural_actions.len());

    CosmeticPayload {
        hide_selectors,
        exceptions,
        generichide: resources.generichide,
        procedural_actions,
        procedural_ignored,
        scriptlets_ignored: !resources.injected_script.is_empty(),
    }
}

#[derive(Clone)]
struct EngineEntry {
    engine: Arc<Engine>,
    stats: RuleBuildStats,
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static ENGINES: OnceLock<RwLock<HashMap<i64, EngineEntry>>> = OnceLock::new();

fn registry() -> &'static RwLock<HashMap<i64, EngineEntry>> {
    ENGINES.get_or_init(|| RwLock::new(HashMap::new()))
}

fn with_engine<T>(handle: jlong, operation: impl FnOnce(&EngineEntry) -> T) -> Option<T> {
    let entry = registry().read().ok()?.get(&handle).cloned()?;
    Some(operation(&entry))
}

fn insert_engine(engine: Engine, mut stats: RuleBuildStats) -> jlong {
    stats.serialized_bytes = engine.serialize().len();
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    let mut guard = registry().write().expect("content block registry poisoned");
    guard.insert(
        handle,
        EngineEntry {
            engine: Arc::new(engine),
            stats,
        },
    );
    handle
}

fn read_java_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(|value| value.into())
        .map_err(|error| format!("JNI string read failed: {error}"))
}

fn read_string_array(env: &mut JNIEnv<'_>, values: jobjectArray) -> Result<Vec<String>, String> {
    if values.is_null() {
        return Ok(Vec::new());
    }
    let array = unsafe { JObjectArray::from_raw(values) };
    let length = env
        .get_array_length(&array)
        .map_err(|error| format!("JNI array length failed: {error}"))? as usize;
    if length > MAX_ARRAY_ITEMS {
        return Err(format!("JNI array item limit exceeded: {length}"));
    }
    let mut output = Vec::with_capacity(length);
    for index in 0..length {
        let object = env
            .get_object_array_element(&array, index as jint)
            .map_err(|error| format!("JNI array item failed: {error}"))?;
        let value = JString::from(object);
        let text = read_java_string(env, value)?;
        if text.len() > MAX_LIST_BYTES {
            return Err("JNI string exceeds safety limit".to_string());
        }
        output.push(text);
    }
    Ok(output)
}

fn unsupported_modifier(lower: &str, stats: &mut RuleBuildStats) -> bool {
    let options = lower.split_once('$').map(|(_, options)| options);
    let Some(options) = options else {
        return false;
    };
    let mut unsupported = false;
    for option in options.split(',') {
        let name = option.trim().trim_start_matches('~');
        if name == "redirect-rule" || name.starts_with("redirect=") {
            stats.unsupported_redirect += 1;
            unsupported = true;
        } else if name == "removeparam" || name.starts_with("removeparam=") {
            stats.unsupported_remove_param += 1;
            unsupported = true;
        } else if name == "csp" || name.starts_with("csp=") {
            stats.unsupported_csp += 1;
            unsupported = true;
        }
    }
    unsupported
}

fn depends_on_document_context(lower: &str) -> bool {
    if lower.contains("##")
        || lower.contains("#@#")
        || lower.contains("#?#")
        || lower.contains("#$#")
    {
        return true;
    }
    let Some((_, options)) = lower.split_once('$') else {
        return false;
    };
    options.split(',').any(|option| {
        let name = option.trim().trim_start_matches('~');
        name == "third-party"
            || name == "document"
            || name == "subdocument"
            || name == "generichide"
            || name == "elemhide"
            || name == "popup"
            || name == "popunder"
            || name.starts_with("domain=")
            || name.starts_with("denyallow=")
    })
}

fn sanitize_filter_list(input: &str, context_free: bool, stats: &mut RuleBuildStats) -> String {
    let mut output = String::with_capacity(input.len().min(MAX_LIST_BYTES));
    for line in input.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('!') || trimmed.starts_with('[') {
            output.push_str(line);
            output.push('\n');
            continue;
        }
        let lower = trimmed.to_ascii_lowercase();
        if lower.contains("##+js(") || lower.contains("#@#+js(") {
            stats.rejected_remote_scriptlet += 1;
            stats.ignored_lines += 1;
            continue;
        }
        if unsupported_modifier(&lower, stats) {
            stats.ignored_lines += 1;
            continue;
        }
        if context_free && depends_on_document_context(&lower) {
            stats.context_dependent_service_worker += 1;
            stats.ignored_lines += 1;
            continue;
        }
        output.push_str(trimmed);
        output.push('\n');
        stats.accepted_lines += 1;
    }
    output
}

fn build_engine(
    filter_lists: Vec<String>,
    context_free: bool,
) -> Result<(Engine, RuleBuildStats), String> {
    if filter_lists.is_empty() || filter_lists.len() > MAX_LISTS {
        return Err(format!("invalid filter list count: {}", filter_lists.len()));
    }
    let total_bytes = filter_lists.iter().map(String::len).sum::<usize>();
    if total_bytes > MAX_LIST_BYTES {
        return Err(format!("filter list byte limit exceeded: {total_bytes}"));
    }

    let mut filter_set = FilterSet::new(false);
    let mut stats = RuleBuildStats::default();
    for list in filter_lists {
        let sanitized = sanitize_filter_list(&list, context_free, &mut stats);
        filter_set.add_filter_list(sanitized, ParseOptions::default());
    }
    Ok((Engine::new_with_filter_set(filter_set), stats))
}

pub fn compile_engine_bytes(
    filter_lists: Vec<String>,
    context_free: bool,
) -> Result<Vec<u8>, String> {
    let (engine, _) = build_engine(filter_lists, context_free)?;
    Ok(engine.serialize())
}

fn make_json_string(env: &mut JNIEnv<'_>, value: &impl Serialize) -> jstring {
    let Ok(json) = serde_json::to_string(value) else {
        return null_mut();
    };
    env.new_string(json)
        .map(|value| value.into_raw())
        .unwrap_or(null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_myAllVideoBrowser_contentblock_nativebridge_AdblockRustNative_nativeVersion(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        env.new_string(NATIVE_VERSION)
            .map(|value| value.into_raw())
            .unwrap_or(null_mut())
    }))
    .unwrap_or(null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_myAllVideoBrowser_contentblock_nativebridge_AdblockRustNative_nativeCreate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    filter_lists: jobjectArray,
    context_free: jboolean,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let lists = read_string_array(&mut env, filter_lists)?;
        let (engine, stats) = build_engine(lists, context_free != 0)?;
        Ok::<jlong, String>(insert_engine(engine, stats))
    }))
    .ok()
    .and_then(Result::ok)
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_myAllVideoBrowser_contentblock_nativebridge_AdblockRustNative_nativeCreateFromSerialized(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    serialized: JByteArray<'_>,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let bytes = env
            .convert_byte_array(&serialized)
            .map_err(|error| format!("JNI byte array read failed: {error}"))?;
        if bytes.is_empty() || bytes.len() > MAX_LIST_BYTES {
            return Err("serialized engine size is invalid".to_string());
        }
        let mut engine = Engine::default();
        engine
            .deserialize(&bytes)
            .map_err(|error| format!("engine deserialize failed: {error:?}"))?;
        Ok::<jlong, String>(insert_engine(engine, RuleBuildStats::default()))
    }))
    .ok()
    .and_then(Result::ok)
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_myAllVideoBrowser_contentblock_nativebridge_AdblockRustNative_nativeEvaluate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    url: JString<'_>,
    source_url: JString<'_>,
    request_type: JString<'_>,
    method: JString<'_>,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let url = read_java_string(&mut env, url)?;
        let source_url = read_java_string(&mut env, source_url)?;
        let request_type = read_java_string(&mut env, request_type)?;
        let method = read_java_string(&mut env, method)?;
        let request = match Request::new(&url, &source_url, &request_type, &method) {
            Ok(request) => request,
            Err(_) => return Ok(DECISION_INVALID_REQUEST),
        };
        Ok(with_engine(handle, |entry| {
            if entry.engine.check_network_request(&request).should_block() {
                DECISION_BLOCK
            } else {
                DECISION_ALLOW
            }
        })
        .unwrap_or(DECISION_ENGINE_MISSING))
    }))
    .ok()
    .and_then(Result::<jint, String>::ok)
    .unwrap_or(DECISION_ENGINE_MISSING)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_myAllVideoBrowser_contentblock_nativebridge_AdblockRustNative_nativeCosmeticResources(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    url: JString<'_>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let url = read_java_string(&mut env, url).ok()?;
        let payload = with_engine(handle, |entry| cosmetic_payload(&entry.engine, &url))?;
        Some(make_json_string(&mut env, &payload))
    }))
    .ok()
    .flatten()
    .unwrap_or(null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_myAllVideoBrowser_contentblock_nativebridge_AdblockRustNative_nativeHiddenSelectors(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    classes: jobjectArray,
    ids: jobjectArray,
    exceptions: jobjectArray,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let classes = read_string_array(&mut env, classes).ok()?;
        let ids = read_string_array(&mut env, ids).ok()?;
        let exceptions = read_string_array(&mut env, exceptions).ok()?;
        if classes
            .iter()
            .chain(&ids)
            .chain(&exceptions)
            .any(|item| item.len() > MAX_ARRAY_ITEM_BYTES)
        {
            return None;
        }
        let exceptions = exceptions.into_iter().collect::<HashSet<_>>();
        let mut selectors = with_engine(handle, |entry| {
            entry
                .engine
                .hidden_class_id_selectors(classes, ids, &exceptions)
        })?;
        selectors.sort_unstable();
        selectors.truncate(MAX_ARRAY_ITEMS);
        Some(make_json_string(&mut env, &selectors))
    }))
    .ok()
    .flatten()
    .unwrap_or(null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_myAllVideoBrowser_contentblock_nativebridge_AdblockRustNative_nativeSerialize(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let bytes = with_engine(handle, |entry| entry.engine.serialize())?;
        env.byte_array_from_slice(&bytes)
            .ok()
            .map(|array| array.into_raw())
    }))
    .ok()
    .flatten()
    .unwrap_or(null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_myAllVideoBrowser_contentblock_nativebridge_AdblockRustNative_nativeStats(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let stats = with_engine(handle, |entry| entry.stats.clone())?;
        Some(make_json_string(&mut env, &stats))
    }))
    .ok()
    .flatten()
    .unwrap_or(null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_myAllVideoBrowser_contentblock_nativebridge_AdblockRustNative_nativeDestroy(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(mut guard) = registry().write() {
            guard.remove(&handle);
        }
    }));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn engine_for(rules: &str, context_free: bool) -> Engine {
        build_engine(vec![rules.to_string()], context_free)
            .expect("engine should build")
            .0
    }

    #[test]
    fn network_rule_blocks_media_and_exception_allows() {
        let engine = engine_for(
            "||ads.example^\n@@||ads.example/player.mp4$domain=video.example\n",
            false,
        );
        let blocked = Request::new(
            "https://ads.example/preroll.m3u8",
            "https://video.example/watch",
            "media",
            "get",
        )
        .unwrap();
        let allowed = Request::new(
            "https://ads.example/player.mp4",
            "https://video.example/watch",
            "media",
            "get",
        )
        .unwrap();
        assert!(engine.check_network_request(&blocked).should_block());
        assert!(!engine.check_network_request(&allowed).should_block());
    }

    #[test]
    fn third_party_and_resource_type_options_remain_contextual() {
        let engine = engine_for("||cdn.example^$third-party,script\n", false);
        let third_party_script = Request::new(
            "https://cdn.example/application.js",
            "https://site.example/page",
            "script",
            "get",
        )
        .unwrap();
        let first_party_script = Request::new(
            "https://cdn.example/application.js",
            "https://cdn.example/page",
            "script",
            "get",
        )
        .unwrap();
        let unknown_resource = Request::new(
            "https://cdn.example/application.js",
            "https://site.example/page",
            "other",
            "get",
        )
        .unwrap();
        assert!(
            engine
                .check_network_request(&third_party_script)
                .should_block()
        );
        assert!(
            !engine
                .check_network_request(&first_party_script)
                .should_block()
        );
        assert!(
            !engine
                .check_network_request(&unknown_resource)
                .should_block()
        );
    }

    #[test]
    fn unmatched_media_is_not_blocked_by_type_alone() {
        let engine = engine_for("||known-media-ads.example^$media\n", false);
        let normal_media = Request::new(
            "https://cdn.example/video/master.m3u8",
            "https://video.example/watch",
            "media",
            "get",
        )
        .unwrap();
        assert!(!engine.check_network_request(&normal_media).should_block());
    }

    #[test]
    fn service_worker_snapshot_drops_context_dependent_rules() {
        let rules = "||global-ads.example^\n||scoped.example^$third-party\n||page.example^$domain=video.example\n";
        let (engine, stats) = build_engine(vec![rules.to_string()], true).unwrap();
        let global = Request::new("https://global-ads.example/a.js", "", "script", "get").unwrap();
        let scoped = Request::new("https://scoped.example/a.js", "", "script", "get").unwrap();
        assert!(engine.check_network_request(&global).should_block());
        assert!(!engine.check_network_request(&scoped).should_block());
        assert_eq!(stats.context_dependent_service_worker, 2);
    }

    #[test]
    fn unsafe_modifiers_and_remote_scriptlets_are_removed() {
        let rules = "||a.example^$redirect=noopjs\n||b.example^$removeparam=token\n||c.example^$csp=script-src\nexample.org##+js(abort-on-property-read,ad)\n||safe.example^\n";
        let (_, stats) = build_engine(vec![rules.to_string()], false).unwrap();
        assert_eq!(stats.unsupported_redirect, 1);
        assert_eq!(stats.unsupported_remove_param, 1);
        assert_eq!(stats.unsupported_csp, 1);
        assert_eq!(stats.rejected_remote_scriptlet, 1);
        assert_eq!(stats.accepted_lines, 1);
    }

    #[test]
    fn cosmetic_resources_include_static_selectors_without_script_source() {
        let engine = engine_for("example.org##.sponsor\nexample.org##+js(noop)\n", false);
        let resources = engine.url_cosmetic_resources("https://example.org/watch");
        assert!(resources.hide_selectors.contains(".sponsor"));
        assert!(resources.injected_script.is_empty());
    }

    #[test]
    fn cosmetic_payload_includes_structured_procedural_rules_without_script_source() {
        let engine = engine_for(
            "example.org##.card:remove()\nexample.org##+js(noop)\n",
            false,
        );
        let direct = engine.url_cosmetic_resources("https://example.org/watch");
        assert!(
            !direct.procedural_actions.is_empty(),
            "expected procedural resources; static={:?}",
            direct.hide_selectors
        );
        let payload = cosmetic_payload(&engine, "https://example.org/watch");
        let encoded = serde_json::to_value(payload).unwrap();
        let rules = encoded["proceduralActions"].as_array().unwrap();

        assert_eq!(rules.len(), 1);
        assert_eq!(rules[0]["selector"][0]["type"], "css-selector");
        assert_eq!(rules[0]["action"]["type"], "remove");
        assert_eq!(encoded["scriptletsIgnored"], false);
        assert!(encoded.get("injectedScript").is_none());
    }

    #[test]
    fn serialized_engine_round_trips() {
        let engine = engine_for("||ads.example^\n", false);
        let bytes = engine.serialize();
        let mut restored = Engine::default();
        restored.deserialize(&bytes).unwrap();
        let request = Request::new(
            "https://ads.example/a.js",
            "https://site.example",
            "script",
            "get",
        )
        .unwrap();
        assert!(restored.check_network_request(&request).should_block());
    }
}
