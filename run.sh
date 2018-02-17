#!/bin/sh
java -cp ~/sapjco3/sapjco3.jar:expense-uploader-0.1-jar-with-dependencies.jar com.sap.expenseuploader.ExpenseUploader --from=20150301 --to=20150501 --controlling-area=0001 --input_erp=system --output_hcp --output_cli --hcp_url="https://<Realspend_HCP_URL>/core/basic/api/v1" --hcp_user=<your_hcp_username> --hcp_password=<your_hcp_password> --hcp_proxy=<proxy-server> --budgets --resume
