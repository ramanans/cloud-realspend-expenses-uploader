package com.sap.expenseuploader.expenses.input;

import com.sap.conn.jco.*;
import com.sap.expenseuploader.Helper;
import com.sap.expenseuploader.config.ErpExpenseInputConfig;
import com.sap.expenseuploader.config.costcenter.CostCenterConfig;
import com.sap.expenseuploader.model.ControllingDocumentData;
import com.sap.expenseuploader.model.Expense;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * Reads expenses from the ERP system, whose configurations should be stored
 * in the file <system-name>.jcoDestination. E.g. system.jcoDestination
 */
public class ErpInput implements ExpenseInput
{
    private static final String BAPI_NAME = "BAPI_ACC_CO_DOCUMENT_FIND";
    private static final String DOC_HEADER_TABLE = "DOC_HEADERS";
    private static final String LINE_ITEMS_TABLE = "LINE_ITEMS";
    private static final String INPUT_DOCUMENT_STRUCTURE = "DOCUMENT";
    private static final String SELECT_CRITERIA_TABLE = "SELECT_CRITERIA";

    private final Logger logger = LogManager.getLogger(this.getClass());

    ErpExpenseInputConfig erpExpenseInputConfig;
    CostCenterConfig costCenterConfig;

    public ErpInput( ErpExpenseInputConfig erpExpenseInputConfig, CostCenterConfig costCenterConfig )
    {
        this.erpExpenseInputConfig = erpExpenseInputConfig;
        this.costCenterConfig = costCenterConfig;
    }

    /**
     * Retrieves expense items from an ERP via JCO.
     *
     * @return
     * @throws JCoException
     */
    @Override
    public List<Expense> getExpenses()
    {
        List<Expense> expenses = new ArrayList<>();

        // Get all expenses via JCO
        try {
            JCoDestination destination = erpExpenseInputConfig.getJcoDestination();
            JCoRepository repository = destination.getRepository();
            JCoContext.begin(destination);
            JCoFunction bapiAccCoDocFind = repository.getFunctionTemplate(BAPI_NAME).getFunction();

            bapiAccCoDocFind.getImportParameterList().setValue("RETURN_ITEMS", "X");
            bapiAccCoDocFind.getImportParameterList().setValue("RETURN_COSTS", "X");

            // Fill BAPI Imports
            JCoStructure input = bapiAccCoDocFind.getImportParameterList().getStructure(INPUT_DOCUMENT_STRUCTURE);
            input.setValue("CO_AREA", erpExpenseInputConfig.getControllingArea());
            if( erpExpenseInputConfig.hasPeriod() ) {
                input.setValue("PERIOD", erpExpenseInputConfig.getPeriod());
            }

            JCoTable table = bapiAccCoDocFind.getTableParameterList().getTable(SELECT_CRITERIA_TABLE);

            // Add date range to the query
            table.appendRow();
            table.setValue("FIELD", "POSTGDATE");
            table.setValue("SIGN", "I");
            table.setValue("OPTION", "BT");
            table.setValue("LOW", erpExpenseInputConfig.getFromTime());
            table.setValue("HIGH", erpExpenseInputConfig.getToTime());

            // Check the existence of the config cost centers in the erp
            Set<String> erpCostCenters = Helper.getErpCostCenters(erpExpenseInputConfig);

            // Add all cost centers to the query
            boolean anyCostCenterExistsInErp = false;
            for( String costCenter : costCenterConfig.getCostCenterList() ) {
                if( erpCostCenters.contains(costCenter) ) {
                    anyCostCenterExistsInErp = true;
                }
                table.appendRow();
                table.setValue("FIELD", "KOSTL");
                table.setValue("SIGN", "I");
                table.setValue("OPTION", "EQ");
                table.setValue("LOW", costCenter);
            }
            if( anyCostCenterExistsInErp ) {
                logger.info(
                    "Will read data from ERP for cost centers " + costCenterConfig.getCostCenterList().toString());
            } else {
                logger.error(
                    "None of these cost centers exist in the ERP: " + costCenterConfig.getCostCenterList().toString());
                return Collections.emptyList();
            }

            // Execute BAPI
            bapiAccCoDocFind.execute(destination);

            // Read returned tables
            JCoTable docHeaders = bapiAccCoDocFind.getTableParameterList().getTable(DOC_HEADER_TABLE);
            JCoTable lineItems = bapiAccCoDocFind.getTableParameterList().getTable(LINE_ITEMS_TABLE);

            // Did we get data?
            if( docHeaders.isEmpty() ) {
                logger.warn("No doc headers!");
            }
            if( lineItems.isEmpty() ) {
                logger.warn("No line items!");
                return null;
            }
            logger.info("Found " + lineItems.getNumRows() + " line items in the ERP");

            // Store temporarily relevant information from the header document
            // in a HashMap to access them efficiently
            HashMap<String, ControllingDocumentData> headerDocumentsMap = new HashMap<>();
            while( !docHeaders.isLastRow() ) {
                ControllingDocumentData value = new ControllingDocumentData(docHeaders.getString("POSTGDATE"),
                    docHeaders.getString("CO_AREA_CURR"));
                String key = docHeaders.getString("DOC_NO");
                headerDocumentsMap.put(key, value);
                docHeaders.nextRow();
            }

            for( int i = 0; i < lineItems.getNumRows(); i++ ) {
                lineItems.setRow(i);

                String documentKey = lineItems.getString("DOC_NO");
                if( !headerDocumentsMap.containsKey(documentKey) ) {
                    logger.info("Key " + documentKey + " not found in header documents table, skipping line item ...");
                    lineItems.nextRow();
                    continue;
                }

                Expense row = new Expense(headerDocumentsMap.get(documentKey).getDocumentDate(),
                    "ACTUAL",
                    Helper.stripLeadingZeros(lineItems.getString("COSTCENTER")),
                    Helper.stripLeadingZeros(lineItems.getString("COST_ELEM")),
                    Helper.stripLeadingZeros(lineItems.getString("PERSON_NO")),
                    Helper.stripLeadingZeros(lineItems.getString("ORDERID")),
                    lineItems.getString("SEG_TEXT"),
                    "",
                    lineItems.getString("VALUE_COCUR"),
                    headerDocumentsMap.get(documentKey).getDocumentCurrency(),
                    Helper.stripLeadingZeros(lineItems.getString("DOC_NO")));
                expenses.add(row);
                logger.debug("Got expense: " + row.toString());
            }
        }
        catch( JCoException e ) {
            logger.error("There was a problem downloading data from the ERP! Please check your jcoDestination file.");
            e.printStackTrace();
            return null;
        }

        return expenses;
    }
}
