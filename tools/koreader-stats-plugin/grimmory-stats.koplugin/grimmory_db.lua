--[[
  grimmory_db.lua — Reads raw per-page statistics from KOReader's statistics.sqlite3 database.

  Only extracts the fields required by the Grimmory stats endpoint:
    - book_md5      (from the book table, joined via id_book)
    - page          (page number)
    - start_time    (Unix timestamp of when the reader arrived on this page)
    - duration      (seconds spent on the page)
    - total_pages   (total pages in the book at time of recording)

  No book metadata, device data, annotations, or highlights are extracted.
--]]

local SQ3 = require("lua-ljsqlite3/init")
local DataStorage = require("datastorage")
local logger = require("logger")

local db_location = DataStorage:getSettingsDir() .. "/statistics.sqlite3"

local GrimmoryDb = {}

--- Attempt to flush in-memory statistics to the database before reading,
--- so we capture data for any book currently open.
local function flush_statistics_to_db()
  local ok, ReaderUI = pcall(require, "apps/reader/readerui")
  if not ok or not ReaderUI or not ReaderUI.instance then return end
  local ui = ReaderUI.instance
  if ui and ui.statistics and ui.statistics.is_doc then
    local flush_ok, err = pcall(function() ui.statistics:insertDB() end)
    if flush_ok then
      logger.info("[GrimmoryStats] Flushed statistics to DB before sync")
    else
      logger.warn("[GrimmoryStats] Failed to flush statistics to DB: " .. tostring(err))
    end
  end
end

--- Returns a list of page-stat records joined with their book MD5 hash.
--- Each record is a table with fields: book_md5, page, start_time, duration, total_pages.
function GrimmoryDb.readPageStats()
  flush_statistics_to_db()

  local conn = SQ3.open(db_location)

  -- Join page_stat_data with book to get md5 per page-turn record.
  -- Columns: page_stat_data(id_book, page, start_time, duration, total_pages)
  --          book(id, md5)
  local query = [[
    SELECT b.md5, psd.page, psd.start_time, psd.duration, psd.total_pages
    FROM page_stat_data AS psd
    JOIN book AS b ON b.id = psd.id_book
    WHERE b.md5 IS NOT NULL
      AND psd.start_time IS NOT NULL
      AND psd.duration IS NOT NULL
  ]]

  local result, rows = conn:exec(query)
  conn:close()

  local stats = {}
  for i = 1, rows do
    table.insert(stats, {
      book_md5   = result[1][i],
      page       = tonumber(result[2][i]),
      start_time = tonumber(result[3][i]),
      duration   = tonumber(result[4][i]),
      total_pages = tonumber(result[5][i]),
    })
  end

  logger.info("[GrimmoryStats] Read " .. #stats .. " page-stat records from database")
  return stats
end

return GrimmoryDb
