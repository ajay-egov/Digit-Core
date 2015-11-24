/*******************************************************************************
 * eGov suite of products aim to improve the internal efficiency,transparency,
 *    accountability and the service delivery of the government  organizations.
 *
 *     Copyright (C) <2015>  eGovernments Foundation
 *
 *     The updated version of eGov suite of products as by eGovernments Foundation
 *     is available at http://www.egovernments.org
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program. If not, see http://www.gnu.org/licenses/ or
 *     http://www.gnu.org/licenses/gpl.html .
 *
 *     In addition to the terms of the GPL license to be adhered to in using this
 *     program, the following additional terms are to be complied with:
 *
 *      1) All versions of this program, verbatim or modified must carry this
 *         Legal Notice.
 *
 *      2) Any misrepresentation of the origin of the material is prohibited. It
 *         is required that all modified versions of this material be marked in
 *         reasonable ways as different from the original version.
 *
 *      3) This license does not grant any rights to any user of the program
 *         with regards to rights under trademark law for use of the trade names
 *         or trademarks of eGovernments Foundation.
 *
 *   In case of any queries, you can reach eGovernments Foundation at contact@egovernments.org
 ******************************************************************************/
package org.egov.ptis.domain.bill;

import static org.egov.demand.interfaces.LatePayPenaltyCalculator.LPPenaltyCalcType.SIMPLE;
import static org.egov.ptis.constants.PropertyTaxConstants.BIGDECIMAL_100;
import static org.egov.ptis.constants.PropertyTaxConstants.DEFAULT_FUNCTIONARY_CODE;
import static org.egov.ptis.constants.PropertyTaxConstants.DEFAULT_FUND_CODE;
import static org.egov.ptis.constants.PropertyTaxConstants.DEFAULT_FUND_SRC_CODE;
import static org.egov.ptis.constants.PropertyTaxConstants.DEMANDRSN_CODE_PENALTY_FINES;
import static org.egov.ptis.constants.PropertyTaxConstants.WF_STATE_CLOSED;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.egov.commons.Installment;
import org.egov.demand.dao.DemandGenericDao;
import org.egov.demand.dao.EgBillDao;
import org.egov.demand.dao.EgDemandDao;
import org.egov.demand.interfaces.Billable;
import org.egov.demand.interfaces.LatePayPenaltyCalculator;
import org.egov.demand.interfaces.RebateCalculator;
import org.egov.demand.model.AbstractBillable;
import org.egov.demand.model.EgBillType;
import org.egov.demand.model.EgDemand;
import org.egov.demand.model.EgDemandDetails;
import org.egov.infra.admin.master.entity.Module;
import org.egov.infra.admin.master.service.ModuleService;
import org.egov.infra.admin.master.service.UserService;
import org.egov.infra.exception.ApplicationRuntimeException;
import org.egov.infstr.utils.HibernateUtil;
import org.egov.infstr.utils.MoneyUtils;
import org.egov.ptis.client.model.PenaltyAndRebate;
import org.egov.ptis.client.util.PropertyTaxUtil;
import org.egov.ptis.constants.PropertyTaxConstants;
import org.egov.ptis.domain.dao.demand.PtDemandDao;
import org.egov.ptis.domain.dao.property.PropertyDAO;
import org.egov.ptis.domain.entity.demand.Ptdemand;
import org.egov.ptis.domain.entity.property.BasicProperty;
import org.egov.ptis.domain.entity.property.Property;
import org.egov.ptis.domain.entity.property.PropertyMutation;
import org.egov.ptis.domain.entity.property.RebatePeriod;
import org.egov.ptis.domain.service.property.PropertyService;
import org.egov.ptis.domain.service.property.RebatePeriodService;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

/**
 * @author satyam
 */
@Service("propertyTaxBillable")
public class PropertyTaxBillable extends AbstractBillable implements Billable, LatePayPenaltyCalculator,
        RebateCalculator {

    private static final String STRING_DEPARTMENT_CODE = "REV";
    private static final String STRING_SERVICE_CODE = "PT";
    private static final String STRING_MUTATION_SERVICE_CODE = "PTMF";
    private BasicProperty basicProperty;
    private Long userId;
    EgBillType egBillType;
    @Autowired
    private EgDemandDao egDemandDAO;
    @Autowired
    private ModuleService moduleDao;
    @Autowired
    private PropertyDAO propertyDAO;
    @Autowired
    @Qualifier("ptDemandDAO")
    private PtDemandDao ptDemandDAO;
    @Autowired
    private EgBillDao egBillDAO;
    @Autowired
    private DemandGenericDao demandGenericDAO;
    @Autowired
    private UserService userService;
    @Autowired
    private PropertyTaxUtil propertyTaxUtil;
    @Autowired
    private ApplicationContext beanProvider;

    private Boolean isCallbackForApportion = Boolean.TRUE;
    private LPPenaltyCalcType penaltyCalcType = SIMPLE;
    private final PropertyTaxUtil ptUtils = new PropertyTaxUtil();
    private String referenceNumber;
    private EgBillType billType;
    private Boolean levyPenalty;
    private Map<Installment, PenaltyAndRebate> instTaxBean = new HashMap<Installment, PenaltyAndRebate>();
    private String collType;
    private String pgType;
    private Map<Installment, EgDemandDetails> installmentWisePenaltyDemandDetail = new TreeMap<Installment, EgDemandDetails>();
    private Boolean mutationFeePayment = Boolean.FALSE;
    private BigDecimal mutationFee;
    private String mutationApplicationNo;
    private String transanctionReferenceNumber;

    private final DateTime PENALTY_EFFECTIVE_DATE_FIRST_HALF = new DateTime().withDayOfMonth(01).withMonthOfYear(07);
    private final DateTime PENALTY_EFFECTIVE_DATE_SECOND_HALF = new DateTime().withMonthOfYear(01).withDayOfMonth(01);

    @Autowired
    private RebatePeriodService rebatePeriodService;

    @Override
    public Boolean getOverrideAccountHeadsAllowed() {
        final Boolean retVal = Boolean.FALSE;
        return retVal;
    }

    @Override
    public Long getUserId() {
        return userId;
    }

    /*
     * (non-Javadoc)
     * @see org.egov.demand.interfaces.Billable#getBillAddres()
     */
    @Override
    public String getBillAddress() {
        return getBasicProperty().getAddress().toString();
    }

    /*
     * (non-Javadoc)
     * @see org.egov.demand.interfaces.Billable#getBillDemand()
     */
    @Override
    public EgDemand getCurrentDemand() {
        BasicProperty bp = null;
        try {
            bp = getBasicProperty();
        } catch (final Exception e) {
            throw new ApplicationRuntimeException("Property does not exist" + e);
        }
        return ptDemandDAO.getNonHistoryCurrDmdForProperty(bp.getProperty());
    }

    /*
     * (non-Javadoc)
     * @see org.egov.demand.interfaces.Billable#getBillLastDueDate()
     */
    @Override
    public Date getBillLastDueDate() {
        return new DateTime().plusMonths(1).toDate();
    }

    /*
     * (non-Javadoc)
     * @see org.egov.demand.interfaces.Billable#getBillPayee()
     */
    @Override
    public String getBillPayee() {
        String payeeName = "";
        if (isMutationFeePayment()) {
            for (final PropertyMutation propertyMutation : getBasicProperty().getPropertyMutations())
                if (!propertyMutation.getState().getValue().equals(WF_STATE_CLOSED))
                    payeeName = propertyMutation.getFullTranfereeName();
        } else
            payeeName = getBasicProperty().getPrimaryOwner().getName();
        return payeeName;
    }

    @Override
    public String getBoundaryType() {
        return PropertyTaxConstants.WARD;
    }

    @Override
    public Long getBoundaryNum() {
        return getBasicProperty().getBoundary().getBoundaryNum();
    }

    @Override
    public Module getModule() {
        return moduleDao.getModuleByName(PropertyTaxConstants.PTMODULENAME);

    }

    @Override
    public String getCollModesNotAllowed() {
        String modesNotAllowed = "";
        BigDecimal chqBouncepenalty = BigDecimal.ZERO;
        final EgDemand currDemand = getCurrentDemand();
        if (currDemand != null && currDemand.getMinAmtPayable() != null
                && currDemand.getMinAmtPayable().compareTo(BigDecimal.ZERO) > 0)
            chqBouncepenalty = getCurrentDemand().getMinAmtPayable();
        if (getUserId() != null && !getUserId().equals("")) {
            final String loginUser = userService.getUserById(getUserId()).getName();
            if (loginUser.equals(PropertyTaxConstants.CITIZENUSER))
                // New Modes for the Client are to be added i.e BlackBerry
                // payment etc.
                modesNotAllowed = "cash,cheque";
            else if (!loginUser.equals(PropertyTaxConstants.CITIZENUSER)
                    && chqBouncepenalty.compareTo(BigDecimal.ZERO) > 0)
                modesNotAllowed = "cheque";
        }
        return modesNotAllowed;
    }

    @Override
    public String getDepartmentCode() {
        return STRING_DEPARTMENT_CODE;

    }

    @Override
    public Date getIssueDate() {
        return new Date();
    }

    @Override
    public Date getLastDate() {
        return getBillLastDueDate();
    }

    @Override
    public String getServiceCode() {
        if (isMutationFeePayment())
            return STRING_MUTATION_SERVICE_CODE;
        else
            return STRING_SERVICE_CODE;
    }

    @Override
    public BigDecimal getTotalAmount() {
        BigDecimal balance = BigDecimal.ZERO;
        if (!isMutationFeePayment()) {
            final EgDemand currentDemand = getCurrentDemand();
            final List instVsAmt = propertyDAO.getDmdCollAmtInstWise(currentDemand);
            for (final Object object : instVsAmt) {
                final Object[] ddObject = (Object[]) object;
                final BigDecimal dmdAmt = (BigDecimal) ddObject[1];
                BigDecimal collAmt = BigDecimal.ZERO;
                if (ddObject[2] != null)
                    collAmt = new BigDecimal((Double) ddObject[2]);
                balance = balance.add(dmdAmt.subtract(collAmt));
                final BigDecimal penaltyAmount = demandGenericDAO.getBalanceByDmdMasterCode(currentDemand,
                        PropertyTaxConstants.PENALTY_DMD_RSN_CODE, getModule());
                if (penaltyAmount != null && penaltyAmount.compareTo(BigDecimal.ZERO) > 0)
                    balance = balance.add(penaltyAmount);
            }
        } else
            balance = getMutationFee();
        return balance;
    }

    @Override
    public String getDescription() {
        return "Property Tax Assessment Number: " + getBasicProperty().getUpicNo();
    }

    /**
     * Method Overridden to get all the Demands (including all the history and
     * non history) for a basicproperty .
     *
     * @return java.util.List<EgDemand>
     */

    @Override
    public List<EgDemand> getAllDemands() {
        List<EgDemand> demands = null;
        final List demandIds = propertyDAO.getAllDemands(getBasicProperty());
        if (demandIds != null && !demandIds.isEmpty()) {
            demands = new ArrayList<EgDemand>();
            final Iterator iter = demandIds.iterator();
            while (iter.hasNext())
                demands.add(egDemandDAO.findById(Long.valueOf(iter.next().toString()), false));
        }
        return demands;
    }

    @Override
    public BigDecimal getFunctionaryCode() {
        return new BigDecimal(DEFAULT_FUNCTIONARY_CODE);
    }

    @Override
    public String getFundCode() {
        return DEFAULT_FUND_CODE;
    }

    @Override
    public String getFundSourceCode() {
        return DEFAULT_FUND_SRC_CODE;
    }

    @Override
    public Boolean getPartPaymentAllowed() {
        if (isMutationFeePayment())
            return false;
        else
            return true;
    }

    @Override
    public String getDisplayMessage() {
        if (isMutationFeePayment())
            return "Mutation Fee Collection";
        else
            return "Property Tax Collection";
    }

    @Override
    public Boolean isCallbackForApportion() {
        return isCallbackForApportion;
    }

    @Override
    public void setCallbackForApportion(final Boolean b) {
        isCallbackForApportion = b;
    }

    @Override
    public BigDecimal calculatePenalty(final Date latestCollReceiptDate, final Date fromDate, final BigDecimal amount) {
        BigDecimal penalty = BigDecimal.ZERO;
        final int noOfMonths = PropertyTaxUtil.getMonthsBetweenDates(fromDate, new Date());
        penalty = amount.multiply(PropertyTaxConstants.PENALTY_PERCENTAGE.multiply(new BigDecimal(noOfMonths))).divide(
                BIGDECIMAL_100);
        return MoneyUtils.roundOff(penalty);
    }

    @Override
    public BigDecimal calculateLPPenaltyForPeriod(final Date fromDate, final Date toDate, final BigDecimal amount) {
        return null;
    }

    @Override
    public LPPenaltyCalcType getLPPenaltyCalcType() {
        return penaltyCalcType;
    }

    @Override
    public BigDecimal getLPPPercentage() {
        return new BigDecimal(ptUtils.getAppConfigValue("LATE_PAYPENALTY_PERC", PropertyTaxConstants.PTMODULENAME));
    }

    @Override
    public void setPenaltyCalcType(final LPPenaltyCalcType penaltyType) {
        penaltyCalcType = penaltyType;
    }

    @Override
    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(final String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    @Override
    public EgBillType getBillType() {
        if (billType == null)
            if (getUserId() != null && !getUserId().equals("")) {
                final String loginUser = userService.getUserById(getUserId()).getName();
                if (loginUser.equals(PropertyTaxConstants.CITIZENUSER))
                    billType = egBillDAO.getBillTypeByCode(PropertyTaxConstants.BILLTYPE_ONLINE);
                else
                    billType = egBillDAO.getBillTypeByCode(PropertyTaxConstants.BILLTYPE_AUTO);
            }
        return billType;
    }

    public void setBillType(final EgBillType billType) {
        this.billType = billType;
    }

    @Override
    public String getConsumerId() {
        if (isMutationFeePayment())
            return mutationApplicationNo;
        else
            return getBasicProperty().getUpicNo();
    }

    private Map<Installment, EgDemandDetails> getInstallmentWisePenaltyDemandDetails(final Property property,
            final Installment currentInstallment) {
        final Map<Installment, EgDemandDetails> installmentWisePenaltyDemandDetails = new TreeMap<Installment, EgDemandDetails>();

        final String query = "select ptd from Ptdemand ptd " + "inner join fetch ptd.egDemandDetails dd "
                + "inner join fetch dd.egDemandReason dr " + "inner join fetch dr.egDemandReasonMaster drm "
                + "inner join fetch ptd.egptProperty p " + "inner join fetch p.basicProperty bp "
                + "where bp.active = true " + "and (p.status = 'A' or p.status = 'I') " + "and p = :property "
                + "and ptd.egInstallmentMaster = :installment " + "and drm.code = :penaltyReasonCode";

        final List list = HibernateUtil.getCurrentSession().createQuery(query).setEntity("property", property)
                .setEntity("installment", currentInstallment)
                .setString("penaltyReasonCode", DEMANDRSN_CODE_PENALTY_FINES).list();

        Ptdemand ptDemand = null;

        if (list.isEmpty()) {
        } else {
            ptDemand = (Ptdemand) list.get(0);
            for (final EgDemandDetails dmdDet : ptDemand.getEgDemandDetails())
                /*
                 * if
                 * (dmdDet.getEgDemandReason().getEgDemandReasonMaster().getCode
                 * () .equalsIgnoreCase(DEMANDRSN_CODE_PENALTY_FINES))
                 */
                installmentWisePenaltyDemandDetails.put(dmdDet.getEgDemandReason().getEgInstallmentMaster(), dmdDet);
        }

        return installmentWisePenaltyDemandDetails;
    }

    public Map<Installment, PenaltyAndRebate> getCalculatedPenalty() {

    	final Map<Installment, PenaltyAndRebate> installmentPenaltyAndRebate = new TreeMap<Installment, PenaltyAndRebate>();
        final int noOfMonths = PropertyTaxUtil.getMonthsBetweenDates(basicProperty.getAssessmentdate(), new Date()) - 1;
        /**
         * Not calculating penalty if collection is happening within two months from the assessment date
         */
        if (noOfMonths <= 2) {
        	return installmentPenaltyAndRebate;
        }
    	
        boolean thereIsBalance = false;

        Installment installment = null;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal collection = BigDecimal.ZERO;
        BigDecimal balance = BigDecimal.ZERO;
        Property property = null;

        if (getLevyPenalty()) {

            final EgDemand currentDemand = getCurrentDemand();
            final Installment currentInstall = currentDemand.getEgInstallmentMaster();
            property = getBasicProperty().getProperty();
            final PropertyService propertyService = beanProvider.getBean("propService", PropertyService.class);
            final Installment assessmentEffecInstallment = propertyService
                    .getAssessmentEffectiveInstallment(basicProperty.getAssessmentdate());

            final Map<String, Map<Installment, BigDecimal>> installmentDemandAndCollection = ptUtils
                    .prepareReasonWiseDenandAndCollection(property, currentInstall);

            installmentWisePenaltyDemandDetail = getInstallmentWisePenaltyDemandDetails(property, currentInstall);

            final Map<Installment, BigDecimal> instWiseDmdMap = installmentDemandAndCollection.get("DEMAND");
            final Map<Installment, BigDecimal> instWiseAmtCollMap = installmentDemandAndCollection.get("COLLECTION");

            PenaltyAndRebate penaltyAndRebate = null;
            EgDemandDetails existingPenaltyDemandDetail = null;

            for (final Map.Entry<Installment, BigDecimal> mapEntry : instWiseDmdMap.entrySet()) {

                installment = mapEntry.getKey();

                tax = mapEntry.getValue();
                collection = instWiseAmtCollMap.get(installment);

                balance = tax.subtract(collection);
                existingPenaltyDemandDetail = installmentWisePenaltyDemandDetail.get(installment);

                thereIsBalance = balance.compareTo(BigDecimal.ZERO) == 1;

                if (thereIsBalance) {
                    penaltyAndRebate = new PenaltyAndRebate();
                    penaltyAndRebate.setRebate(calculateEarlyPayRebate(tax));

                    if (existingPenaltyDemandDetail == null) {
                        final Date penaltyEffectiveDate = getPenaltyEffectiveDate(installment,
                                assessmentEffecInstallment, basicProperty.getAssessmentdate());
                        if (penaltyEffectiveDate.before(new Date()))
                            penaltyAndRebate.setPenalty(calculatePenalty(null, penaltyEffectiveDate, balance));
                    } else
                        penaltyAndRebate.setPenalty(existingPenaltyDemandDetail.getAmount().subtract(
                                existingPenaltyDemandDetail.getAmtCollected()));
                    installmentPenaltyAndRebate.put(installment, penaltyAndRebate);
                }
            }
        }

        return installmentPenaltyAndRebate;
    }

    private Date getPenaltyEffectiveDate(final Installment installment, final Installment assessmentEffecInstallment,
            final Date assmentDate) {
        final DateTime installmentDate = new DateTime(installment.getFromDate());
        final DateTime installmentToDate = new DateTime(installment.getToDate());
        final DateTime firstHalfPeriod = new DateTime(PENALTY_EFFECTIVE_DATE_FIRST_HALF.toDate())
                .withYear(installmentDate.getYear());
        final DateTime secondHalfPeriod = new DateTime(PENALTY_EFFECTIVE_DATE_SECOND_HALF.toDate())
                .withYear(installmentToDate.getYear());
        /**
         * If assessment date falls in the installment on which penalty is being
         * calculated then penalty calculation will be effective from two months
         * after the assessment date
         */
        if (installment.equals(assessmentEffecInstallment)) {
            final Calendar penalyDate = Calendar.getInstance();
            penalyDate.setTime(assmentDate);
            penalyDate.add(Calendar.MONTH, 2);
            penalyDate.set(Calendar.DAY_OF_MONTH, 1);
            return penalyDate.getTime();
        } else if (propertyTaxUtil
                .between(firstHalfPeriod.toDate(), installment.getFromDate(), installment.getToDate()))
            return firstHalfPeriod.toDate();
        else
            return secondHalfPeriod.toDate();
    }

    @Override
    public BigDecimal calculateEarlyPayRebate(final BigDecimal tax) {
        if (isEarlyPayRebateActive())
            return tax.multiply(PropertyTaxConstants.ADVANCE_REBATE_PERCENTAGE).divide(BIGDECIMAL_100);
        else
            return BigDecimal.ZERO;
    }

    @Override
    public boolean isEarlyPayRebateActive() {
        boolean value = false;
        final Installment currentInstallment = PropertyTaxUtil.getCurrentInstallment();
        final RebatePeriod rebatePeriod = rebatePeriodService.getRebateForCurrInstallment(currentInstallment.getId());
        if (rebatePeriod != null)
            if (rebatePeriod.getRebateDate().compareTo(new Date()) != 1)
                value = true;
        return value;
    }

    public void setUserId(final Long userId) {
        this.userId = userId;
    }

    public BasicProperty getBasicProperty() {
        return basicProperty;
    }

    public void setBasicProperty(final BasicProperty basicProperty) {
        this.basicProperty = basicProperty;
    }

    public Boolean getLevyPenalty() {
        return levyPenalty;
    }

    public void setLevyPenalty(final Boolean levyPenalty) {
        this.levyPenalty = levyPenalty;
    }

    public Map<Installment, PenaltyAndRebate> getInstTaxBean() {
        return instTaxBean;
    }

    public void setInstTaxBean(final Map<Installment, PenaltyAndRebate> instTaxBean) {
        this.instTaxBean = instTaxBean;
    }

    public void setCollectionType(final String collType) {
        this.collType = collType;
    }

    public String getCollectionType() {
        return collType;
    }

    public void setPaymentGatewayType(final String pgType) {
        this.pgType = pgType;
    }

    public String getPaymentGatewayType() {
        return pgType;
    }

    public BigDecimal getMutationFee() {
        return mutationFee;
    }

    public void setMutationFee(final BigDecimal mutationFee) {
        this.mutationFee = mutationFee;
    }

    public boolean isMutationFeePayment() {
        return mutationFeePayment;
    }

    public void setMutationFeePayment(final boolean mutationFeePayment) {
        this.mutationFeePayment = mutationFeePayment;
    }

    public void setMutationApplicationNo(final String mutationApplicationNo) {
        this.mutationApplicationNo = mutationApplicationNo;
    }

    @Override
    public String getTransanctionReferenceNumber() {
        return transanctionReferenceNumber;
    }

    public void setTransanctionReferenceNumber(final String transanctionReferenceNumber) {
        this.transanctionReferenceNumber = transanctionReferenceNumber;
    }
}
