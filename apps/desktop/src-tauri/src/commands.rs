use serde::{Deserialize, Serialize};
use serde_json::Value;
use tauri::AppHandle;

#[derive(Debug, Deserialize)]
pub struct ToolInvokeArgs {
    pub capability: String,
    pub args: Value,
}

#[derive(Debug, Serialize)]
#[serde(tag = "kind")]
pub enum ToolError {
    #[serde(rename = "permissionDenied")]
    PermissionDenied { tool: String, permission: String },
    #[serde(rename = "failed")]
    Failed { tool: String, message: String },
}

#[tauri::command]
pub async fn tool_invoke(
    app: AppHandle,
    capability: String,
    args: Value,
) -> Result<Value, ToolError> {
    let req = ToolInvokeArgs { capability, args };
    crate::tools::dispatch(&app, &req.capability, req.args).await
}
