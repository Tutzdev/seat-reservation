-- Generic cell rate algorithm (GCRA): a limit of N requests per period allows a burst of N, then one request
-- every period / N. A single value per client is enough: the theoretical arrival time (TAT) of its next
-- request, which moves one interval forward per request and never falls behind the clock.
--
-- KEYS[1]  the client's TAT (epoch milliseconds)
-- ARGV[1]  now, ARGV[2] the interval (period / N), both in milliseconds; ARGV[3] the burst, N
-- ARGV[4]  1 to take a request, -1 to give one back
--
-- Returns 0 when the request may go ahead, otherwise how many milliseconds until it would.
local now = tonumber(ARGV[1])
local interval = tonumber(ARGV[2])
local burst = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])

local tat = tonumber(redis.call('GET', KEYS[1])) or now
if tat < now then
    tat = now
end

local next = tat + interval * cost
if cost > 0 then
    local wait = next - interval * burst - now
    if wait > 0 then
        return wait
    end
end

if next <= now then
    redis.call('DEL', KEYS[1])
else
    redis.call('SET', KEYS[1], string.format('%.0f', next), 'PX', next - now)
end
return 0
