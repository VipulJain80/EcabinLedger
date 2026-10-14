package com.ecabin.ledger.service;

import static com.ecabin.ledger.api.FormModels.*;
import java.util.EnumMap;
import java.util.Map;

final class MaintenanceActionFormRules {
    private MaintenanceActionFormRules() {}
    static void requireValid(FormConfiguration config) {
        if(config==null||config.fields()==null||config.fields().size()!=3||config.fields().stream().anyMatch(field->field.type()==FieldType.SECTION))throw new IllegalArgumentException("A maintenance action form must contain exactly three visible business fields: status, SLA Target Date, and optional Remarks.");
        Map<DefectFieldBinding,FieldDefinition> fields=new EnumMap<>(DefectFieldBinding.class);
        for(FieldDefinition field:config.fields())if(field.binding()!=null&&isMaintenanceBinding(field.binding())){
            if(fields.put(field.binding(),field)!=null)throw new IllegalArgumentException("Maintenance action bindings cannot be duplicated.");
        }
        FieldDefinition status=fields.get(DefectFieldBinding.MAINTENANCE_STATUS),date=fields.get(DefectFieldBinding.SLA_TARGET_DATE),remark=fields.get(DefectFieldBinding.MAINTENANCE_REMARK);
        if(status==null||date==null||remark==null)throw new IllegalArgumentException("Configure Maintenance Status, required SLA Target Date, and optional Remarks before publishing this corrective-action form.");
        if(status.type()!=FieldType.DROPDOWN||!status.required()||status.optionSource()!=OptionSource.MAINTENANCE_ACTION_STATUSES||Boolean.FALSE.equals(status.visible())||Boolean.TRUE.equals(status.readOnly()))throw new IllegalArgumentException("Maintenance Status must be a visible, editable, required dropdown using the approved status options.");
        if(date.type()!=FieldType.DATE||!date.required()||Boolean.FALSE.equals(date.visible())||Boolean.TRUE.equals(date.readOnly()))throw new IllegalArgumentException("SLA Target Date must be a visible, editable, required date field.");
        if(remark.type()!=FieldType.TEXTAREA||remark.required()||remark.textRules()==null||remark.textRules().maxLength()==null||remark.textRules().maxLength()>2000||Boolean.FALSE.equals(remark.visible())||Boolean.TRUE.equals(remark.readOnly()))throw new IllegalArgumentException("Remarks must be a visible, editable, optional multiline field with a maximum length of 2,000 characters.");
        if(fields.size()!=3)throw new IllegalArgumentException("A maintenance action form supports only the status, SLA Target Date, and optional Remarks business fields.");
    }
    private static boolean isMaintenanceBinding(DefectFieldBinding binding){return binding==DefectFieldBinding.MAINTENANCE_STATUS||binding==DefectFieldBinding.SLA_TARGET_DATE||binding==DefectFieldBinding.MAINTENANCE_REMARK;}
}
