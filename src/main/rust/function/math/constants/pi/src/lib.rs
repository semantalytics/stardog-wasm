use std::ffi::CString;
use std::os::raw::c_char;
use serde_json::json;
use std::f64::consts;

pub use stardog_function::*;

#[no_mangle]
pub extern fn evaluate(_subject: *mut c_char) -> *mut c_char {

    let sparql_query_result = json!({
      "head": {"vars":["result"]}, "results":{"bindings":[{"result":{"type":"literal","value": consts::PI}}]}
    }).to_string();

    return unsafe { CString::from_vec_unchecked(sparql_query_result.into_bytes()) }.into_raw();
}
