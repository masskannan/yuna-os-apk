{.push exportc, dynlib, cdecl.}

var ringHashes: array[100, uint64]
var ringIdx: int = 0

# FNV-1a / 64-bit Fast DOM Deduplicator Guard (Last 100 messages)
proc isDuplicateDom*(hashVal: uint64): cint =
  for i in 0..99:
    if ringHashes[i] == hashVal:
      return 1
  ringHashes[ringIdx] = hashVal
  ringIdx = (ringIdx + 1) mod 100
  return 0

proc verifyDomProgress*(beforeHash: uint64, afterHash: uint64): cint =
  if beforeHash != afterHash:
    return 1
  return 0

{.pop.}
