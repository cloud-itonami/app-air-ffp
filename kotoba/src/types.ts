/**
 * air-ffp kotoba — frequent-flyer program, kotoba-E2E split.
 *
 * Per ADR-2606011400 (Consensys product-front / infra-back) + ADR-2605172400
 * (3-axis OR-test) + ADR-2605181100 (kotoba E2E encrypted-record envelope) +
 * ADR-2605172100 (etzhayyim never the fiat MoR). Founder directive 2026-06-03:
 * MAXIMAL migration — front everything that can move; only the irreducible
 * regulated EXECUTION stays etzhayyim.
 *
 * SPLIT:
 *   PUBLIC (plaintext AT records, no subject PII):
 *     - tierBenefit: program reference catalog (tier name, qualifying-miles
 *       threshold, benefit lines, partner code). Open program metadata.
 *     - tierSummary: aggregate read-view per carrier/tier (member_count,
 *       avgTotalMiles). De-identified rollup, frontable plaintext.
 *
 *   SENSITIVE / PII + ledger (kotoba E2E, com.etzhayyim.encrypted.record):
 *     - memberProfile: enrollee PII (name, email, nationality, member DID) +
 *       balances/tier. Sealed via sdk.encryptedWrite; read-cap = owner DID +
 *       explicit recipients. Per-person record never substrate-plaintext.
 *     - milesLedger: per-member ledger entries (accrual / redemption / transfer
 *       / mile-purchase / expiry / partner-reconcile). The DATA migrates E2E;
 *       money carried as decimal STRINGS. FK ledger → memberProfile via
 *       memberNumber (exists() within the owner's E2E view).
 *
 *   STAYS etzhayyim (NOT a collection — consumed via consent-capability):
 *     - the fiat merchant-of-record SETTLEMENT rail: the card/bank charge for a
 *       mile PURCHASE, the transfer-FEE collection, and the IATA-BSP partner
 *       fiat-clearing CALL. etzhayyim never becomes the fiat counterparty
 *       (on-chain USDC only), so the settlement EXECUTION stays etzhayyim while every
 *       ledger ENTRY is fronted here as an E2E record.
 *
 * AT-Lexicon: no float. Counts/miles/thresholds are integers; money (fees,
 * settlement, price-per-mile, totals) are decimal STRINGS; percent/scores are
 * integer 0-100.
 */

// ─── Plaintext collections ──────────────────────────────────────────
export const TIER_BENEFIT_COLLECTION = "com.etzhayyim.apps.airFfp.tierBenefit";
export const TIER_SUMMARY_COLLECTION = "com.etzhayyim.apps.airFfp.tierSummary";

// ─── E2E inner-type NSIDs (body shape inside the kotoba envelope) ────
export const MEMBER_PROFILE_INNER_TYPE = "com.etzhayyim.apps.airFfp.memberProfile";
export const MILES_LEDGER_INNER_TYPE = "com.etzhayyim.apps.airFfp.milesLedger";

export const FFP_DID_PREFIX = "did:web:air-ffp.etzhayyim.com:" as const;

// ─── tierBenefit (PLAINTEXT, public program catalog) ────────────────

export interface TierBenefitRecord {
  did: string;
  tierCode: string;
  carrierCode: string;
  displayName: string;
  /** integer — qualifying miles to reach/hold this tier. */
  qualifyingMiles: number;
  benefits: string[];
  partnerCode?: string;
  createdAt: string;
}
export interface TierBenefitView extends TierBenefitRecord {
  benefitUri: string;
}
export interface RegisterTierBenefitInput {
  tierCode: string;
  carrierCode: string;
  displayName: string;
  qualifyingMiles: number;
  benefits?: string[];
  partnerCode?: string;
}
export interface RegisterTierBenefitOutput {
  status: "registered" | "alreadyExists" | "rejected";
  benefitUri?: string;
  did?: string;
  tierCode?: string;
  error?: string;
}
export interface ListTierBenefitsInput {
  carrierCode?: string;
  limit?: number;
  cursor?: string;
}
export interface ListTierBenefitsOutput {
  items: TierBenefitView[];
  cursor?: string;
  total: number;
}

// ─── tierSummary (PLAINTEXT, aggregate de-identified read-view) ─────

export interface TierSummaryRecord {
  did: string;
  carrierCode: string;
  tierCode: string;
  /** integer — active members in this carrier/tier bucket. */
  memberCount: number;
  /** integer — average total miles (rounded to whole mile). */
  avgTotalMiles: number;
  asOf: string;
  createdAt: string;
}
export interface TierSummaryView extends TierSummaryRecord {
  summaryUri: string;
}
export interface RecordTierSummaryInput {
  carrierCode: string;
  tierCode: string;
  memberCount: number;
  avgTotalMiles: number;
  asOf?: string;
}
export interface RecordTierSummaryOutput {
  status: "recorded" | "rejected";
  summaryUri?: string;
  did?: string;
  error?: string;
}
export interface ListTierSummaryInput {
  carrierCode?: string;
  limit?: number;
  cursor?: string;
}
export interface ListTierSummaryOutput {
  items: TierSummaryView[];
  cursor?: string;
  total: number;
}

// ─── memberProfile (E2E-ENCRYPTED, PII) ─────────────────────────────

export interface MemberProfileBody {
  memberNumber: string;
  memberDid: string;
  firstName: string;
  lastName: string;
  email: string;
  nationality?: string;
  carrierCode: string;
  tierCode: string;
  /** integer miles. */
  milesBalance: number;
  /** integer miles. */
  qualifyingMiles: number;
  status: string;
  enrolledAt: string;
}
export interface MemberProfileView extends MemberProfileBody {
  uri: string;
  sender: string;
  createdAt: string;
}
export interface EnrollMemberInput {
  memberNumber: string;
  firstName: string;
  lastName: string;
  email: string;
  carrierCode: string;
  nationality?: string;
  tierCode?: string;
  milesBalance?: number;
  qualifyingMiles?: number;
  status?: string;
  enrolledAt?: string;
  /** Extra DIDs to grant read-cap (owner always included). */
  recipients?: string[];
}
export interface EnrollMemberOutput {
  status: "enrolled" | "rejected";
  uri?: string;
  keyId?: string;
  memberNumber?: string;
  memberDid?: string;
  error?: string;
}
export interface ListMembersInput {
  carrierCode?: string;
  tierCode?: string;
  limit?: number;
  cursor?: string;
}
export interface ListMembersOutput {
  items: MemberProfileView[];
  cursor?: string;
  total: number;
}
export interface GetMemberInput {
  memberNumber: string;
}
export interface GetMemberOutput {
  member?: MemberProfileView;
  error?: string;
}

// ─── milesLedger (E2E-ENCRYPTED, per-member ledger) ─────────────────

export type LedgerEntryKind =
  | "accrual"
  | "redemption"
  | "transfer"
  | "purchase"
  | "expiry"
  | "reconcile";

export interface MilesLedgerBody {
  entryId: string;
  memberNumber: string;
  kind: LedgerEntryKind;
  /** integer miles moved (signed by kind semantics; magnitude only). */
  miles: number;
  /** optional reference (flight no / reward code / transfer ref / period). */
  reference?: string;
  partnerCode?: string;
  /** money as decimal STRING — fee / settlement / total (fiat rail stays etzhayyim). */
  amount?: string;
  currency?: string;
  /** money as decimal STRING — price per mile for purchases. */
  pricePerMile?: string;
  status: string;
  occurredAt: string;
}
export interface MilesLedgerView extends MilesLedgerBody {
  uri: string;
  sender: string;
  createdAt: string;
}
export interface PostLedgerInput {
  entryId: string;
  memberNumber: string;
  kind: LedgerEntryKind;
  miles: number;
  reference?: string;
  partnerCode?: string;
  amount?: string;
  currency?: string;
  pricePerMile?: string;
  status?: string;
  occurredAt?: string;
  recipients?: string[];
}
export interface PostLedgerOutput {
  status: "posted" | "rejected";
  uri?: string;
  keyId?: string;
  entryId?: string;
  error?: string;
}
export interface ListLedgerInput {
  memberNumber?: string;
  kind?: LedgerEntryKind;
  limit?: number;
  cursor?: string;
}
export interface ListLedgerOutput {
  items: MilesLedgerView[];
  cursor?: string;
  total: number;
}
export interface GetLedgerEntryInput {
  entryId: string;
}
export interface GetLedgerEntryOutput {
  entry?: MilesLedgerView;
  error?: string;
}

// ─── Coverage rollup ────────────────────────────────────────────────

export interface CoverageInput {
  maxScan?: number;
}
export interface CoverageOutput {
  tierBenefitCount?: number;
  tierSummaryCount?: number;
  memberProfileCount?: number;
  milesLedgerCount?: number;
  benefitsByCarrier?: Record<string, number>;
  ledgerByKind?: Record<string, number>;
  truncated?: boolean;
  error?: string;
}

// ─── Validation + helpers ───────────────────────────────────────────

const LEDGER_KINDS: ReadonlyArray<LedgerEntryKind> = [
  "accrual",
  "redemption",
  "transfer",
  "purchase",
  "expiry",
  "reconcile",
];

export function isUint(n: unknown): n is number {
  return typeof n === "number" && Number.isInteger(n) && n >= 0;
}
export function isLedgerKind(k: unknown): k is LedgerEntryKind {
  return typeof k === "string" && (LEDGER_KINDS as readonly string[]).includes(k);
}
/** Decimal money string: digits with optional single fractional part, no sign. */
export function isDecimalString(s: unknown): s is string {
  return typeof s === "string" && /^\d+(\.\d+)?$/.test(s);
}
export function memberDidFor(memberNumber: string): string {
  return `${FFP_DID_PREFIX}m:${memberNumber.toLowerCase()}`;
}
function slug(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}
export function benefitRkey(carrierCode: string, tierCode: string): string {
  return `benefit-${slug(carrierCode)}-${slug(tierCode)}`;
}
export function benefitDidFor(carrierCode: string, tierCode: string): string {
  return `${FFP_DID_PREFIX}benefit:${slug(carrierCode)}:${slug(tierCode)}`;
}
export function summaryRkey(carrierCode: string, tierCode: string): string {
  return `summary-${slug(carrierCode)}-${slug(tierCode)}`;
}
export function summaryDidFor(carrierCode: string, tierCode: string): string {
  return `${FFP_DID_PREFIX}summary:${slug(carrierCode)}:${slug(tierCode)}`;
}
export function memberRkey(memberNumber: string): string {
  return `member-${slug(memberNumber)}`;
}
export function ledgerRkey(entryId: string): string {
  return `ledger-${slug(entryId)}`;
}
