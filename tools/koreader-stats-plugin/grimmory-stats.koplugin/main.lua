--[[
  main.lua — Grimmory Stats Plugin entry point.

  This plugin reads raw per-page statistics from KOReader's local statistics
  database and POSTs them to the Grimmory backend at:

      POST [SERVER_URL]/api/koreader/syncs/stats

  Authentication re-uses the standard KOReader sync credentials (username +
  MD5 key), so no separate configuration is required beyond the server URL,
  username, and password you already use for KOReader progress sync.

  Installation:
    1. Copy this directory (grimmory-stats.koplugin) into KOReader's plugins/ folder.
    2. Restart KOReader.
    3. In the main menu → Tools → Grimmory Stats, set your server URL,
       username, and password.
    4. Tap "Sync now" to push statistics immediately, or enable "Sync on suspend"
       for automatic background syncing.
--]]

local _ = require("gettext")
local Dispatcher = require("dispatcher")
local InfoMessage = require("ui/widget/infomessage")
local InputDialog = require("ui/widget/inputdialog")
local logger = require("logger")
local MultiInputDialog = require("ui/widget/multiinputdialog")
local UIManager = require("ui/uimanager")
local WidgetContainer = require("ui/widget/container/widgetcontainer")

local GrimmoryApi = require("grimmory_api")
local GrimmoryDb = require("grimmory_db")
local GrimmorySettings = require("grimmory_settings")

local GrimmoryStats = WidgetContainer:extend({
  name = "grimmory_stats",
  is_doc_only = false,
})

-- ============================================================================
-- Initialisation
-- ============================================================================

function GrimmoryStats:init()
  self:onDispatcherRegisterActions()
  self.ui.menu:registerToMainMenu(self)
  self.settings = GrimmorySettings:new({})
end

-- ============================================================================
-- KOReader suspend hook
-- ============================================================================

function GrimmoryStats:onSuspend()
  if self.settings:getSyncOnSuspend() then
    logger.info("[GrimmoryStats] Suspend detected, triggering background sync")
    self:performSync(true)
  end
end

-- ============================================================================
-- Menu
-- ============================================================================

function GrimmoryStats:addToMainMenu(menu_items)
  menu_items.grimmory_stats = {
    text = _("Grimmory Stats"),
    sorting_hint = "tools",
    sub_item_table = {
      {
        text = _("Sync now"),
        callback = function()
          self:performSync(false)
        end,
        separator = true,
      },
      {
        text = _("Sync on suspend"),
        checked_func = function()
          return self.settings:getSyncOnSuspend()
        end,
        callback = function()
          self.settings:update({ sync_on_suspend = not self.settings:getSyncOnSuspend() })
        end,
        separator = true,
      },
      {
        text = _("Configure server…"),
        keep_menu_open = true,
        callback = function()
          self:showConfigDialog()
        end,
      },
      {
        text = _("About"),
        callback = function()
          UIManager:show(InfoMessage:new({
            text = _(
              "Grimmory Stats Plugin\n\n" ..
              "Syncs your KOReader reading statistics to Grimmory.\n\n" ..
              "Authentication uses the same credentials as KOReader's built-in progress sync."
            ),
          }))
        end,
      },
    },
  }
end

-- ============================================================================
-- Configuration dialog
-- ============================================================================

function GrimmoryStats:showConfigDialog()
  local fields = {
    {
      text = self.settings:getServerURL(),
      hint = _("https://your-grimmory-server"),
      description = _("Server URL"),
    },
    {
      text = self.settings:getUsername(),
      hint = _("KOReader username"),
      description = _("Username"),
    },
    {
      text = self.settings:getPassword(),
      hint = _("KOReader password"),
      description = _("Password"),
      text_type = "password",
    },
  }

  local dialog
  dialog = MultiInputDialog:new({
    title = _("Grimmory Stats — Server Configuration"),
    fields = fields,
    buttons = {
      {
        {
          text = _("Cancel"),
          id = "close",
          callback = function()
            UIManager:close(dialog)
          end,
        },
        {
          text = _("Save"),
          callback = function()
            local values = dialog:getFields()
            self.settings:update({
              server_url = values[1],
              username   = values[2],
              password   = values[3],
            })
            UIManager:close(dialog)
            UIManager:show(InfoMessage:new({ text = _("Settings saved.") }))
          end,
        },
      },
    },
  })
  UIManager:show(dialog)
end

-- ============================================================================
-- Sync logic
-- ============================================================================

function GrimmoryStats:performSync(silent)
  local server_url = self.settings:getServerURL()
  local username   = self.settings:getUsername()
  local password   = self.settings:getPassword()

  if server_url == "" or username == "" or password == "" then
    if not silent then
      UIManager:show(InfoMessage:new({
        text = _("Please configure your server URL, username, and password first."),
      }))
    end
    return
  end

  local stats = GrimmoryDb.readPageStats()

  if #stats == 0 then
    if not silent then
      UIManager:show(InfoMessage:new({ text = _("No statistics found to sync.") }))
    end
    logger.info("[GrimmoryStats] No stats to sync")
    return
  end

  logger.info("[GrimmoryStats] Syncing " .. #stats .. " page-stat records to " .. server_url)

  local ok, response = GrimmoryApi.postStats(server_url, username, password, stats)

  if not silent then
    if ok then
      local inserted = (response and response.inserted) or 0
      local updated  = (response and response.updated)  or 0
      UIManager:show(InfoMessage:new({
        text = _("Sync complete. Sessions saved: ") .. inserted .. _(", updated: ") .. updated,
      }))
    else
      local detail = type(response) == "string" and (" (" .. response .. ")") or ""
      UIManager:show(InfoMessage:new({
        text = _("Sync failed. Check your server URL and credentials.") .. detail,
      }))
    end
  end
end

-- ============================================================================
-- Dispatcher registration
-- ============================================================================

function GrimmoryStats:onDispatcherRegisterActions()
  Dispatcher:registerAction("grimmory_stats_sync", {
    category = "none",
    event    = "GrimmoryStatsSync",
    title    = _("Grimmory Stats: Sync now"),
    general  = true,
  })
end

function GrimmoryStats:onGrimmoryStatsSync()
  self:performSync(false)
end

return GrimmoryStats
