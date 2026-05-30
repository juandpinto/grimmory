--[[
  grimmory_settings.lua — Handles persistent settings for the Grimmory Stats Plugin.

  Settings are stored in a file called "grimmory.lua" inside KOReader's settings
  directory (alongside other plugin settings).
--]]

local DataStorage = require("datastorage")
local LuaSettings = require("luasettings")
local logger = require("logger")

local GrimmorySettings = {}
GrimmorySettings.__index = GrimmorySettings

local SETTING_KEY = "grimmory"
local DEFAULTS = {
  server_url = "",
  username = "",
  password = "",
  sync_on_suspend = false,
}

local function open_settings_handle()
  local path = DataStorage:getSettingsDir() .. "/" .. SETTING_KEY .. ".lua"
  return LuaSettings:open(path)
end

function GrimmorySettings:new()
  local obj = setmetatable({}, self)
  obj._handle = open_settings_handle()
  local ok, data = pcall(function()
    return obj._handle:readSetting(SETTING_KEY, {}) or {}
  end)
  obj.data = ok and data or {}
  return obj
end

function GrimmorySettings:_save()
  local ok, err = pcall(function()
    self._handle:saveSetting(SETTING_KEY, self.data)
    self._handle:flush()
  end)
  if not ok then
    logger.err("[GrimmoryStats] Failed to save settings:", err)
    return false
  end
  return true
end

function GrimmorySettings:update(patch)
  for k, v in pairs(patch or {}) do
    self.data[k] = v
  end
  return self:_save()
end

function GrimmorySettings:getServerURL()
  return self.data.server_url or DEFAULTS.server_url
end

function GrimmorySettings:getUsername()
  return self.data.username or DEFAULTS.username
end

function GrimmorySettings:getPassword()
  return self.data.password or DEFAULTS.password
end

function GrimmorySettings:getSyncOnSuspend()
  if self.data.sync_on_suspend == nil then
    return DEFAULTS.sync_on_suspend
  end
  return self.data.sync_on_suspend
end

return GrimmorySettings
