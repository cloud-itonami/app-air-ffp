import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
  registerTierBenefit,
  listTierBenefits,
  recordTierSummary,
  listTierSummary,
  enrollMember,
  listMembers,
  getMember,
  postLedgerEntry,
  listLedger,
  getLedgerEntry,
  coverage,
} from "../src/index.js";

const OWNER = "did:web:air-ffp.etzhayyim.com";

describe("air-ffp kotoba (kotoba-E2E split)", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: OWNER });
  });

  describe("tierBenefit (PLAINTEXT public catalog)", () => {
    it("registers, dedups, validates, lists/filters", async () => {
      expect((await registerTierBenefit(e, { tierCode: "gold", carrierCode: "NH", displayName: "Gold", qualifyingMiles: 50000, benefits: ["lounge"] })).status).toBe("registered");
      expect((await registerTierBenefit(e, { tierCode: "gold", carrierCode: "NH", displayName: "Gold", qualifyingMiles: 50000 })).status).toBe("alreadyExists");
      expect((await registerTierBenefit(e, { tierCode: "x", carrierCode: "NH", displayName: "X", qualifyingMiles: -1 })).status).toBe("rejected");
      await registerTierBenefit(e, { tierCode: "plat", carrierCode: "JL", displayName: "Platinum", qualifyingMiles: 80000 });
      expect((await listTierBenefits(e)).total).toBe(2);
      expect((await listTierBenefits(e, { carrierCode: "NH" })).total).toBe(1);
    });
  });

  describe("tierSummary (PLAINTEXT de-identified aggregate)", () => {
    it("records latest-wins, validates integers, lists/filters", async () => {
      expect((await recordTierSummary(e, { carrierCode: "NH", tierCode: "gold", memberCount: 1200, avgTotalMiles: 64000 })).status).toBe("recorded");
      // latest-wins idempotent bucket
      await recordTierSummary(e, { carrierCode: "NH", tierCode: "gold", memberCount: 1300, avgTotalMiles: 65000 });
      expect((await recordTierSummary(e, { carrierCode: "NH", tierCode: "gold", memberCount: -1, avgTotalMiles: 0 })).status).toBe("rejected");
      await recordTierSummary(e, { carrierCode: "JL", tierCode: "plat", memberCount: 400, avgTotalMiles: 90000 });
      const all = await listTierSummary(e);
      expect(all.total).toBe(2);
      const nh = all.items.find((s: any) => s.carrierCode === "NH");
      expect(nh.memberCount).toBe(1300);
      expect((await listTierSummary(e, { carrierCode: "JL" })).total).toBe(1);
    });
  });

  describe("memberProfile (E2E-ENCRYPTED PII)", () => {
    it("seals via encryptedWrite, round-trips via encryptedRead, validates", async () => {
      const ok = await enrollMember(e, { memberNumber: "M001", firstName: "Aki", lastName: "Sato", email: "aki@example.com", carrierCode: "NH", tierCode: "gold", milesBalance: 64000, qualifyingMiles: 51000, nationality: "JP" });
      expect(ok.status).toBe("enrolled");
      expect(ok.keyId).toBeTruthy();
      expect(ok.memberDid).toContain("air-ffp.etzhayyim.com");
      expect((await enrollMember(e, { memberNumber: "MX", firstName: "A", lastName: "B", email: "a@b", carrierCode: "NH", milesBalance: -5 })).status).toBe("rejected");
      const got = await getMember(e, { memberNumber: "M001" });
      expect(got.member?.email).toBe("aki@example.com");
      expect(got.member?.milesBalance).toBe(64000);
      await enrollMember(e, { memberNumber: "M002", firstName: "Ken", lastName: "Ito", email: "ken@example.com", carrierCode: "JL", tierCode: "base" });
      expect((await listMembers(e)).total).toBe(2);
      expect((await listMembers(e, { carrierCode: "NH" })).total).toBe(1);
      expect((await listMembers(e, { tierCode: "base" })).total).toBe(1);
    });

    it("enforces read-cap: a non-recipient DID cannot decrypt the member", async () => {
      await enrollMember(e, { memberNumber: "M001", firstName: "Aki", lastName: "Sato", email: "aki@example.com", carrierCode: "NH" });
      const outsider: any = new MockEtzhayyim({ did: "did:web:outsider.example" });
      expect((await listMembers(outsider)).total).toBe(0);
    });

    it("grants read-cap to an explicit recipient", async () => {
      const partner = "did:web:partner.example";
      const r = await enrollMember(e, { memberNumber: "M001", firstName: "Aki", lastName: "Sato", email: "aki@example.com", carrierCode: "NH", recipients: [partner] });
      expect(r.status).toBe("enrolled");
      expect((await listMembers(e)).total).toBe(1);
    });
  });

  describe("milesLedger (E2E-ENCRYPTED ledger; FK → member; money decimal strings)", () => {
    it("posts accrual/redemption/purchase, validates, FK-gates, round-trips", async () => {
      await enrollMember(e, { memberNumber: "M001", firstName: "Aki", lastName: "Sato", email: "aki@example.com", carrierCode: "NH" });
      // FK fail: no member M999
      expect((await postLedgerEntry(e, { entryId: "L0", memberNumber: "M999", kind: "accrual", miles: 100 })).status).toBe("rejected");
      const acc = await postLedgerEntry(e, { entryId: "L1", memberNumber: "M001", kind: "accrual", miles: 1200, reference: "NH006", partnerCode: "NH" });
      expect(acc.status).toBe("posted");
      expect(acc.keyId).toBeTruthy();
      // purchase carries fiat money as decimal strings (settlement CALL stays etzhayyim)
      const buy = await postLedgerEntry(e, { entryId: "L2", memberNumber: "M001", kind: "purchase", miles: 5000, amount: "150.00", currency: "USD", pricePerMile: "0.03" });
      expect(buy.status).toBe("posted");
      expect((await postLedgerEntry(e, { entryId: "LB", memberNumber: "M001", kind: "purchase", miles: 1, amount: "abc" })).status).toBe("rejected");
      expect((await postLedgerEntry(e, { entryId: "LK", memberNumber: "M001", kind: "bogus" as any, miles: 1 })).status).toBe("rejected");
      expect((await postLedgerEntry(e, { entryId: "LM", memberNumber: "M001", kind: "accrual", miles: -3 })).status).toBe("rejected");
      const got = await getLedgerEntry(e, { entryId: "L2" });
      expect(got.entry?.amount).toBe("150.00");
      expect(got.entry?.pricePerMile).toBe("0.03");
      expect((await listLedger(e)).total).toBe(2);
      expect((await listLedger(e, { memberNumber: "M001" })).total).toBe(2);
      expect((await listLedger(e, { kind: "purchase" })).total).toBe(1);
    });

    it("ledger is read-cap isolated from a non-recipient", async () => {
      await enrollMember(e, { memberNumber: "M001", firstName: "A", lastName: "B", email: "a@b", carrierCode: "NH" });
      await postLedgerEntry(e, { entryId: "L1", memberNumber: "M001", kind: "accrual", miles: 100 });
      const outsider: any = new MockEtzhayyim({ did: "did:web:outsider.example" });
      expect((await listLedger(outsider)).total).toBe(0);
    });
  });

  describe("coverage rollup", () => {
    it("counts plaintext catalog/summary + E2E members/ledger by carrier & kind", async () => {
      await registerTierBenefit(e, { tierCode: "gold", carrierCode: "NH", displayName: "Gold", qualifyingMiles: 50000 });
      await registerTierBenefit(e, { tierCode: "plat", carrierCode: "NH", displayName: "Plat", qualifyingMiles: 80000 });
      await recordTierSummary(e, { carrierCode: "NH", tierCode: "gold", memberCount: 10, avgTotalMiles: 1000 });
      await enrollMember(e, { memberNumber: "M001", firstName: "A", lastName: "B", email: "a@b", carrierCode: "NH" });
      await postLedgerEntry(e, { entryId: "L1", memberNumber: "M001", kind: "accrual", miles: 100 });
      await postLedgerEntry(e, { entryId: "L2", memberNumber: "M001", kind: "redemption", miles: 50 });
      const cov = await coverage(e);
      expect(cov.tierBenefitCount).toBe(2);
      expect(cov.tierSummaryCount).toBe(1);
      expect(cov.memberProfileCount).toBe(1);
      expect(cov.milesLedgerCount).toBe(2);
      expect(cov.benefitsByCarrier?.NH).toBe(2);
      expect(cov.ledgerByKind?.accrual).toBe(1);
      expect(cov.ledgerByKind?.redemption).toBe(1);
    });
  });
});
