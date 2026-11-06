-- Gives a customer a place in an event's waiting room and returns when they are let in (epoch milliseconds).
--
-- KEYS[1]  the room's tail: the admission time handed out last
-- KEYS[2]  this customer's admission time
-- ARGV[1]  now, ARGV[2] the interval between two admissions, ARGV[3] how long a pass lasts (all milliseconds)
--
-- Every newcomer is let in one interval after the one before, or right away when the room is empty, so the
-- room admits at a steady pace however many arrive at once. Redis runs a script alone, so two customers never
-- get the same slot. Joining again while the pass is still valid keeps the place already given.
local now = tonumber(ARGV[1])
local interval = tonumber(ARGV[2])
local pass = tonumber(ARGV[3])

local mine = tonumber(redis.call('GET', KEYS[2]))
if mine and mine + pass > now then
    return mine
end

local admitted = now
local tail = tonumber(redis.call('GET', KEYS[1]))
if tail and tail + interval > now then
    admitted = tail + interval
end

-- Keys only expire to free memory; whether a pass is still valid is decided by the caller's clock.
local keep = admitted - now + pass
redis.call('SET', KEYS[1], string.format('%.0f', admitted), 'PX', keep)
redis.call('SET', KEYS[2], string.format('%.0f', admitted), 'PX', keep)
return admitted
