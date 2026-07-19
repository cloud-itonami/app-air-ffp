/**
 * air-ffp kotoba — registry.
 *
 * Plaintext path (tierBenefit, tierSummary): sdk.write / sdk.read — public
 * program catalog + de-identified aggregate read-views.
 * E2E path (memberProfile, milesLedger): sdk.encryptedWrite / sdk.encryptedRead
 * — PII + per-member ledger entries sealed in the kotoba envelope
 * (ADR-2605181100), read-cap = owner DID + explicit recipients. The substrate
 * never sees enrollee PII or ledger detail in plaintext.
 *
 * The fiat merchant-of-record settlement rail (mile-purchase charge, transfer
 * fee, IATA-BSP partner clearing) stays etzhayyim and is consumed via
 * consent-capability; here we only persist the resulting ledger ENTRY.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  TIER_BENEFIT_COLLECTION,
  TIER_SUMMARY_COLLECTION,
  MEMBER_PROFILE_INNER_TYPE,
  MILES_LEDGER_INNER_TYPE,
  benefitDidFor,
  benefitRkey,
  isDecimalString,
  isLedgerKind,
  isUint,
  ledgerRkey,
  memberDidFor,
  memberRkey,
  summaryDidFor,
  summaryRkey,
  type CoverageInput,
  type CoverageOutput,
  type EnrollMemberInput,
  type EnrollMemberOutput,
  type GetLedgerEntryInput,
  type GetLedgerEntryOutput,
  type GetMemberInput,
  type GetMemberOutput,
  type ListLedgerInput,
  type ListLedgerOutput,
  type ListMembersInput,
  type ListMembersOutput,
  type ListTierBenefitsInput,
  type ListTierBenefitsOutput,
  type ListTierSummaryInput,
  type ListTierSummaryOutput,
  type MemberProfileBody,
  type MemberProfileView,
  type MilesLedgerBody,
  type MilesLedgerView,
  type PostLedgerInput,
  type PostLedgerOutput,
  type RecordTierSummaryInput,
  type RecordTierSummaryOutput,
  type RegisterTierBenefitInput,
  type RegisterTierBenefitOutput,
  type TierBenefitRecord,
  type TierBenefitView,
  type TierSummaryRecord,
  type TierSummaryView,
} from "./types.js";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;

// ─── tierBenefit (PLAINTEXT, public program catalog) ────────────────

export async function registerTierBenefit(e: Etzhayyim, input: RegisterTierBenefitInput): Promise<RegisterTierBenefitOutput> {
  if (!input.tierCode || !input.carrierCode || !input.displayName) return { status: "rejected", error: "missingRequiredFields" };
  if (!isUint(input.qualifyingMiles)) return { status: "rejected", error: "invalidQualifyingMiles" };
  const rkey = benefitRkey(input.carrierCode, input.tierCode);
  const existing = await e.read<TierBenefitRecord>({ collection: TIER_BENEFIT_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", benefitUri: existing.records[0].uri, did: existing.records[0].value.did, tierCode: input.tierCode };
  }
  const now = new Date().toISOString();
  const did = benefitDidFor(input.carrierCode, input.tierCode);
  const record: TierBenefitRecord = {
    did,
    tierCode: input.tierCode,
    carrierCode: input.carrierCode,
    displayName: input.displayName,
    qualifyingMiles: input.qualifyingMiles,
    benefits: input.benefits ?? [],
    partnerCode: input.partnerCode,
    createdAt: now,
  };
  const receipt = await e.write({ collection: TIER_BENEFIT_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "registered", benefitUri: receipt.uri, did, tierCode: input.tierCode };
}

export async function listTierBenefits(e: Etzhayyim, input: ListTierBenefitsInput = {}): Promise<ListTierBenefitsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<TierBenefitRecord>({ collection: TIER_BENEFIT_COLLECTION, cursor: input.cursor, limit });
  const items: TierBenefitView[] = resp.records
    .filter((r) => !input.carrierCode || r.value.carrierCode === input.carrierCode)
    .map((r) => ({ ...r.value, benefitUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── tierSummary (PLAINTEXT, de-identified aggregate read-view) ─────

export async function recordTierSummary(e: Etzhayyim, input: RecordTierSummaryInput): Promise<RecordTierSummaryOutput> {
  if (!input.carrierCode || !input.tierCode) return { status: "rejected", error: "missingRequiredFields" };
  if (!isUint(input.memberCount)) return { status: "rejected", error: "invalidMemberCount" };
  if (!isUint(input.avgTotalMiles)) return { status: "rejected", error: "invalidAvgTotalMiles" };
  const now = new Date().toISOString();
  const did = summaryDidFor(input.carrierCode, input.tierCode);
  const record: TierSummaryRecord = {
    did,
    carrierCode: input.carrierCode,
    tierCode: input.tierCode,
    memberCount: input.memberCount,
    avgTotalMiles: input.avgTotalMiles,
    asOf: input.asOf ?? now,
    createdAt: now,
  };
  // Latest-wins per carrier/tier bucket (idempotent rkey).
  const receipt = await e.write({ collection: TIER_SUMMARY_COLLECTION, record: record as unknown as Record<string, unknown>, rkey: summaryRkey(input.carrierCode, input.tierCode) });
  return { status: "recorded", summaryUri: receipt.uri, did };
}

export async function listTierSummary(e: Etzhayyim, input: ListTierSummaryInput = {}): Promise<ListTierSummaryOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<TierSummaryRecord>({ collection: TIER_SUMMARY_COLLECTION, cursor: input.cursor, limit });
  const items: TierSummaryView[] = resp.records
    .filter((r) => !input.carrierCode || r.value.carrierCode === input.carrierCode)
    .map((r) => ({ ...r.value, summaryUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── memberProfile (E2E-ENCRYPTED, PII) ─────────────────────────────

export async function enrollMember(e: Etzhayyim, input: EnrollMemberInput): Promise<EnrollMemberOutput> {
  if (!input.memberNumber || !input.firstName || !input.lastName || !input.email || !input.carrierCode) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  if (input.milesBalance !== undefined && !isUint(input.milesBalance)) return { status: "rejected", error: "invalidMilesBalance" };
  if (input.qualifyingMiles !== undefined && !isUint(input.qualifyingMiles)) return { status: "rejected", error: "invalidQualifyingMiles" };
  const memberDid = memberDidFor(input.memberNumber);
  const body: MemberProfileBody = {
    memberNumber: input.memberNumber,
    memberDid,
    firstName: input.firstName,
    lastName: input.lastName,
    email: input.email,
    nationality: input.nationality,
    carrierCode: input.carrierCode,
    tierCode: input.tierCode ?? "base",
    milesBalance: input.milesBalance ?? 0,
    qualifyingMiles: input.qualifyingMiles ?? 0,
    status: input.status ?? "active",
    enrolledAt: input.enrolledAt ?? new Date().toISOString(),
  };
  const receipt = await e.encryptedWrite<Record<string, unknown>>({
    innerType: MEMBER_PROFILE_INNER_TYPE,
    record: body as unknown as Record<string, unknown>,
    recipients: input.recipients ?? [],
    rkey: memberRkey(input.memberNumber),
  });
  return { status: "enrolled", uri: receipt.uri, keyId: receipt.keyId, memberNumber: input.memberNumber, memberDid };
}

async function scanMembers(e: Etzhayyim, maxScan: number): Promise<MemberProfileView[]> {
  const out: MemberProfileView[] = [];
  let cursor: string | undefined;
  while (out.length < maxScan) {
    const page = await e.encryptedRead<MemberProfileBody>({ innerType: MEMBER_PROFILE_INNER_TYPE, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) out.push({ ...r.value, uri: r.uri, sender: r.sender, createdAt: r.createdAt });
    if (!page.cursor || page.records.length === 0) break;
    cursor = page.cursor;
  }
  return out;
}

export async function listMembers(e: Etzhayyim, input: ListMembersInput = {}): Promise<ListMembersOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const all = await scanMembers(e, DEFAULT_MAX_SCAN);
  const filtered = all.filter(
    (m) => (!input.carrierCode || m.carrierCode === input.carrierCode) && (!input.tierCode || m.tierCode === input.tierCode),
  );
  return { items: filtered.slice(0, limit), total: filtered.length };
}

export async function getMember(e: Etzhayyim, input: GetMemberInput): Promise<GetMemberOutput> {
  if (!input.memberNumber) return { error: "invalidMemberNumber" };
  const all = await scanMembers(e, DEFAULT_MAX_SCAN);
  const found = all.find((m) => m.memberNumber === input.memberNumber);
  if (!found) return { error: "notFound" };
  return { member: found };
}

/** FK helper: does an E2E member profile exist in the owner's read-cap view? */
async function memberExists(e: Etzhayyim, memberNumber: string): Promise<boolean> {
  const all = await scanMembers(e, DEFAULT_MAX_SCAN);
  return all.some((m) => m.memberNumber === memberNumber);
}

// ─── milesLedger (E2E-ENCRYPTED, per-member ledger; FK → member) ────

export async function postLedgerEntry(e: Etzhayyim, input: PostLedgerInput): Promise<PostLedgerOutput> {
  if (!input.entryId || !input.memberNumber || !input.kind) return { status: "rejected", error: "missingRequiredFields" };
  if (!isLedgerKind(input.kind)) return { status: "rejected", error: "invalidKind" };
  if (!isUint(input.miles)) return { status: "rejected", error: "invalidMiles" };
  if (input.amount !== undefined && !isDecimalString(input.amount)) return { status: "rejected", error: "invalidAmount" };
  if (input.pricePerMile !== undefined && !isDecimalString(input.pricePerMile)) return { status: "rejected", error: "invalidPricePerMile" };
  if (!(await memberExists(e, input.memberNumber))) return { status: "rejected", error: "memberNotFound" };
  const body: MilesLedgerBody = {
    entryId: input.entryId,
    memberNumber: input.memberNumber,
    kind: input.kind,
    miles: input.miles,
    reference: input.reference,
    partnerCode: input.partnerCode,
    amount: input.amount,
    currency: input.currency,
    pricePerMile: input.pricePerMile,
    status: input.status ?? "posted",
    occurredAt: input.occurredAt ?? new Date().toISOString(),
  };
  const receipt = await e.encryptedWrite<Record<string, unknown>>({
    innerType: MILES_LEDGER_INNER_TYPE,
    record: body as unknown as Record<string, unknown>,
    recipients: input.recipients ?? [],
    rkey: ledgerRkey(input.entryId),
  });
  return { status: "posted", uri: receipt.uri, keyId: receipt.keyId, entryId: input.entryId };
}

async function scanLedger(e: Etzhayyim, maxScan: number): Promise<MilesLedgerView[]> {
  const out: MilesLedgerView[] = [];
  let cursor: string | undefined;
  while (out.length < maxScan) {
    const page = await e.encryptedRead<MilesLedgerBody>({ innerType: MILES_LEDGER_INNER_TYPE, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) out.push({ ...r.value, uri: r.uri, sender: r.sender, createdAt: r.createdAt });
    if (!page.cursor || page.records.length === 0) break;
    cursor = page.cursor;
  }
  return out;
}

export async function listLedger(e: Etzhayyim, input: ListLedgerInput = {}): Promise<ListLedgerOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const all = await scanLedger(e, DEFAULT_MAX_SCAN);
  const filtered = all.filter(
    (x) => (!input.memberNumber || x.memberNumber === input.memberNumber) && (!input.kind || x.kind === input.kind),
  );
  return { items: filtered.slice(0, limit), total: filtered.length };
}

export async function getLedgerEntry(e: Etzhayyim, input: GetLedgerEntryInput): Promise<GetLedgerEntryOutput> {
  if (!input.entryId) return { error: "invalidEntryId" };
  const all = await scanLedger(e, DEFAULT_MAX_SCAN);
  const found = all.find((x) => x.entryId === input.entryId);
  if (!found) return { error: "notFound" };
  return { entry: found };
}

// ─── Coverage rollup (plaintext + E2E countAll) ─────────────────────

export async function coverage(e: Etzhayyim, input: CoverageInput = {}): Promise<CoverageOutput> {
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);

  const benefitsByCarrier: Record<string, number> = {};
  let tierBenefitCount = 0;
  let cursor: string | undefined;
  while (tierBenefitCount < maxScan) {
    const page = await e.read<TierBenefitRecord>({ collection: TIER_BENEFIT_COLLECTION, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      benefitsByCarrier[r.value.carrierCode] = (benefitsByCarrier[r.value.carrierCode] ?? 0) + 1;
      tierBenefitCount += 1;
    }
    if (!page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }

  let tierSummaryCount = 0;
  let summaryCursor: string | undefined;
  while (tierSummaryCount < maxScan) {
    const page = await e.read<TierSummaryRecord>({ collection: TIER_SUMMARY_COLLECTION, cursor: summaryCursor, limit: PAGE_LIMIT });
    tierSummaryCount += page.records.length;
    if (!page.cursor || page.records.length < PAGE_LIMIT) break;
    summaryCursor = page.cursor;
  }

  const members = await scanMembers(e, maxScan);
  const ledger = await scanLedger(e, maxScan);
  const ledgerByKind: Record<string, number> = {};
  for (const x of ledger) ledgerByKind[x.kind] = (ledgerByKind[x.kind] ?? 0) + 1;

  return {
    tierBenefitCount,
    tierSummaryCount,
    memberProfileCount: members.length,
    milesLedgerCount: ledger.length,
    benefitsByCarrier,
    ledgerByKind,
    truncated: tierBenefitCount >= maxScan || members.length >= maxScan || ledger.length >= maxScan,
  };
}
