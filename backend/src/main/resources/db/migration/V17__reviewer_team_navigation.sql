INSERT INTO navigation_menu_items(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,'review-queue','Reviewer queue',p.id,'REVIEW|ADMINISTER_ORGANIZATION','◷','review-queue',45
FROM operators o JOIN navigation_menu_items p ON p.operator_id=o.id AND p.menu_key='operations'
ON CONFLICT DO NOTHING;

INSERT INTO navigation_menu_items(operator_id,menu_key,label,parent_id,required_permissions,icon,route_key,display_order)
SELECT o.id,'reviewer-routing','Reviewer routing',p.id,'ADMINISTER_ORGANIZATION','⇢','reviewer-routing',95
FROM operators o JOIN navigation_menu_items p ON p.operator_id=o.id AND p.menu_key='configuration'
ON CONFLICT DO NOTHING;
