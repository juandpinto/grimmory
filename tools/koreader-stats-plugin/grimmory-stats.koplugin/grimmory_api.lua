--[[
  grimmory_api.lua — Sends reading statistics to the Grimmory backend.

  Authentication follows KOReader's native sync protocol:
    x-auth-user: <username>
    x-auth-key:  md5(username + password)

  This means users configure only one set of credentials — the same ones they
  use for the native KOReader sync — and no separate plugin account is needed.
--]]

local JSON = require("json")
local ltn12 = require("ltn12")
local logger = require("logger")
local md5 = require("ffi/sha2").md5
local socket = require("socket")
local socketutil = require("socketutil")
local http = require("socket.http")

-- server_url is the full KOReader API base (e.g. https://host/api/koreader),
-- matching what Grimmory's settings UI shows under "KOReader API Path".
local STATS_PATH = "/syncs/stats"

local GrimmoryApi = {}

--- Computes the MD5 auth key expected by KoreaderAuthFilter.
--- KOReader's own KOSync plugin sends md5(password) — the password alone,
--- NOT md5(username .. password). The backend stores and compares md5(password).
local function compute_auth_key(username, password)
  return md5(password)
end

--- POST the stats payload to Grimmory.
--- Returns: ok (boolean), response_body or error_string
function GrimmoryApi.postStats(server_url, username, password, stats)
  if not server_url or server_url == "" then
    logger.err("[GrimmoryStats] No server URL configured")
    return false, "no_server_url"
  end
  if not username or username == "" then
    logger.err("[GrimmoryStats] No username configured")
    return false, "no_username"
  end
  if not password or password == "" then
    logger.err("[GrimmoryStats] No password configured")
    return false, "no_password"
  end

  local url = server_url .. STATS_PATH
  local body = JSON.encode({ stats = stats })
  local auth_key = compute_auth_key(username, password)

  local headers = {
    ["Content-Type"]   = "application/json",
    ["Content-Length"] = tostring(#body),
    ["x-auth-user"]    = username,
    ["x-auth-key"]     = auth_key,
  }

  local sink = {}
  local request = {
    method  = "POST",
    url     = url,
    headers = headers,
    source  = ltn12.source.string(body),
    sink    = ltn12.sink.table(sink),
  }

  socketutil:set_timeout(socketutil.LARGE_BLOCK_TIMEOUT, socketutil.LARGE_TOTAL_TIMEOUT)
  logger.dbg("[GrimmoryStats] POST", url)

  local code, resp_headers, status = socket.skip(1, http.request(request))
  socketutil:reset_timeout()

  if resp_headers == nil then
    logger.err("[GrimmoryStats] Network error:", status or code)
    return false, "network_error"
  end

  if code ~= 200 then
    logger.err("[GrimmoryStats] HTTP error:", code, status)
    return false, "http_" .. tostring(code)
  end

  local content = table.concat(sink)
  local parse_ok, result = pcall(JSON.decode, content)
  if parse_ok and result then
    return true, result
  end

  logger.warn("[GrimmoryStats] Could not parse response JSON:", content)
  return true, {}
end

return GrimmoryApi
