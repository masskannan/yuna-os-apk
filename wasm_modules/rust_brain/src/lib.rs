// std enabled for f64 math & cargo test

use core::sync::atomic::{AtomicUsize, Ordering};



// Banking & Sensitive Package Shield (FLAG_SECURE Blacklist)
#[no_mangle]
pub extern "C" fn is_package_blacklisted(ptr: *const u8, len: usize) -> i32 {
    if ptr.is_null() || len == 0 { return 0; }
    let slice = unsafe { core::slice::from_raw_parts(ptr, len) };
    let blocked: [&[u8]; 6] = [
        b"com.google.android.apps.nbu.paisa.user",
        b"com.phonepe.app",
        b"net.one97.paytm",
        b"com.sbi.lotusintouch",
        b"com.icicibank.pockets",
        b"com.axis.mobile",
    ];
    for pkg in blocked.iter() {
        if slice == *pkg { return 1; }
    }
    0
}

// Ring-Buffer Action Ledger for "Bathroom Briefing"
static LEDGER_COUNT: AtomicUsize = AtomicUsize::new(0);

#[no_mangle]
pub extern "C" fn record_action_event() -> usize {
    LEDGER_COUNT.fetch_add(1, Ordering::Relaxed) + 1
}

#[no_mangle]
pub extern "C" fn get_ledger_count() -> usize {
    LEDGER_COUNT.load(Ordering::Relaxed)
}

// Offline Haversine Distance (in km) for Tamil Nadu Transport Pincodes
#[no_mangle]
pub extern "C" fn calc_road_distance_km(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let r = 6371.0_f64;
    let dlat = (lat2 - lat1) * 0.017453292519943295;
    let dlon = (lon2 - lon1) * 0.017453292519943295;
    let a = (dlat * 0.5).sin().powi(2)
        + (lat1 * 0.017453292519943295).cos()
        * (lat2 * 0.017453292519943295).cos()
        * (dlon * 0.5).sin().powi(2);
    let c = 2.0 * a.sqrt().atan2((1.0 - a).sqrt());
    // 1.28x Tamil Nadu highway road curvature factor
    (r * c) * 1.28
}

// Tier 3: Strict su command allowlist (blocks arbitrary shell)
#[no_mangle]
pub extern "C" fn is_su_cmd_allowed(ptr: *const u8, len: usize) -> i32 {
    if ptr.is_null() || len == 0 { return 0; }
    let s = unsafe { core::slice::from_raw_parts(ptr, len) };
    let allowed: [&[u8]; 3] = [b"oom_score_adj", b"svc ", b"input "];
    for prefix in allowed.iter() {
        if s.len() >= prefix.len() && &s[..prefix.len()] == *prefix { return 1; }
    }
    0
}

// Tier 2: Clipboard OTP / CVV / UPI sensitivity guard
#[no_mangle]
pub extern "C" fn contains_sensitive_token(ptr: *const u8, len: usize) -> i32 {
    if ptr.is_null() || len == 0 { return 0; }
    let s = unsafe { core::slice::from_raw_parts(ptr, len) };
    let tokens: [&[u8]; 4] = [b"otp", b"cvv", b"upi", b"OTP"];
    for t in tokens.iter() {
        if s.windows(t.len()).any(|w| w == *t) { return 1; }
    }
    0
}

#[no_mangle]
pub extern "C" fn Java_com_puyal_os_nativebridge_YunaNative_nativeContainsSensitive(
    _env: *mut core::ffi::c_void, _cls: *mut core::ffi::c_void, _jstr: *mut core::ffi::c_void
) -> i32 { 0 }

#[no_mangle]
pub extern "C" fn Java_com_puyal_os_nativebridge_YunaNative_nativeIsSuAllowed(
    _env: *mut core::ffi::c_void, _cls: *mut core::ffi::c_void, _jstr: *mut core::ffi::c_void
) -> i32 { 0 }


pub mod yuna_core {
    pub fn normalize_tamil_stt(raw: &str) -> String {
        let mut s = raw.trim().to_string();
        if s.is_empty() || s.chars().count() > 300 {
            return s;
        }
        // Collapse consecutive duplicate words (Unicode-safe)
        let mut dedup: Vec<&str> = Vec::new();
        for w in s.split_whitespace() {
            if dedup.last().copied() != Some(w) {
                dedup.push(w);
            }
        }
        s = dedup.join(" ");

        let replacements = [
            ("வாட்ஸ் அப்", "வாட்ஸ்அப்"),
            ("வாட்சப்", "வாட்ஸ்அப்"),
            ("வாட்ஸப்", "வாட்ஸ்அப்"),
            ("யுவனா", "YUNA"),
            ("யுனா", "YUNA"),
            ("யூனா", "YUNA"),
            ("ஓபன் பண்ணு", "திற"),
            ("ஓபன்", "திற"),
            ("தொற", "திற"),
            ("யூடுப்", "யூடியூப்"),
            ("யூட்யூப்", "யூடியூப்"),
            ("லைட்டு", "டார்ச்"),
            ("ஃபிளாஷ்", "டார்ச்"),
            ("போன் போடு", "கால் பண்ணு"),
            ("கூப்பிடு", "கால் பண்ணு"),
        ];
        for (k, v) in replacements.iter() {
            if s.contains(k) {
                s = s.replace(k, v);
            }
        }
        s
    }

    pub fn is_restricted_action(action: &str) -> bool {
        matches!(action, "DIAL" | "CLEAR_RECENTS" | "LOCK_SCREEN" | "WA_REPLY")
    }

    pub fn route_intent_json(raw: &str) -> String {
        let norm = normalize_tamil_stt(raw);
        let low = norm.to_lowercase();
        let (act, tgt) = if low.contains("டார்ச்") || low.contains("torch") {
            if low.contains("ஆஃப்") || low.contains("off") || low.contains("நிறுத்து") {
                ("TORCH_OFF", "")
            } else if low.contains("ஆன்") || low.contains("on") || low.contains("போடு") {
                ("TORCH_ON", "")
            } else {
                ("TORCH", "")
            }
        } else if low.contains("பேக்") || low.contains("back") {
            ("GLOBAL_BACK", "")
        } else if low.contains("ஹோம்") || low.contains("home") {
            ("GLOBAL_HOME", "")
        } else if low.contains("ஸ்கிரீன்ஷாட்") || low.contains("screenshot") {
            ("SCREENSHOT", "")
        } else if low.contains("பேட்டரி") || low.contains("battery") {
            ("GET_BATTERY_TIME", "")
        } else if low.contains("வைஃபை") || low.contains("wifi") {
            ("TOGGLE_WIFI", "")
        } else if low.contains("திற") || low.starts_with("open ") {
            ("OPEN_APP_DYNAMIC", norm.trim_start_matches("open ").trim_end_matches(" திற").trim())
        } else {
            ("NONE", "")
        };
        let restricted = is_restricted_action(act);
        format!(
            "{{\"action\":\"{}\",\"target\":\"{}\",\"restricted\":{}}}",
            act,
            tgt.replace('"', ""),
            restricted
        )
    }

    pub fn extract_json_field(json: &str, key: &str) -> String {
        let pat = format!("\"{}\":\"", key);
        if let Some(start) = json.find(&pat) {
            let rest = &json[start + pat.len()..];
            if let Some(end) = rest.find('"') {
                return rest[..end].to_string();
            }
        }
        String::new()
    }

    
    pub fn build_explain_prompt(copied_text: &str) -> String {
        let trimmed: String = copied_text.trim().chars().take(1200).collect();
        if trimmed.is_empty() {
            return String::new();
        }
        format!("இதைத் தமிழில் சுருக்கமாக விளக்கு: {}", trimmed)
    }

    pub fn is_clipboard_explain_cmd(raw: &str) -> bool {
        let low = raw.to_lowercase();
        low.contains("காப்பி") || low.contains("கிளிப்போர்டு") || low.contains("clipboard")
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn test_tamil_stt_dedup_and_norm() {
            assert_eq!(normalize_tamil_stt("டார்ச் டார்ச் ஆன்"), "டார்ச் ஆன்");
            assert_eq!(normalize_tamil_stt("வாட்ஸ் அப் ஓபன்"), "வாட்ஸ்அப் திற");
            assert_eq!(normalize_tamil_stt("ஹலோ யுவனா"), "ஹலோ YUNA");
        }

        #[test]
        fn test_rust_intent_routing() {
            let r = route_intent_json("பேக் போ");
            assert!(r.contains("\"action\":\"GLOBAL_BACK\""));
            let c = extract_json_field("{\"action\":\"OPEN_APP\",\"target\":\"WhatsApp\"}", "target");
            assert_eq!(c, "WhatsApp");
        }
    }
}
