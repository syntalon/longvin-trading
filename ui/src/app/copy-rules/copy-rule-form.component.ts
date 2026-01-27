import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute } from '@angular/router';
import { CopyRuleService, CopyRule, CreateCopyRuleRequest, UpdateCopyRuleRequest } from '../copy-rule.service';

@Component({
  selector: 'app-copy-rule-form',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './copy-rule-form.component.html',
  styleUrl: './copy-rule-form.component.scss'
})
export class CopyRuleFormComponent implements OnInit {
  rule: Partial<CopyRule> = {
    primaryAccountNumber: '',
    shadowAccountNumber: '',
    ratioType: 'MULTIPLIER',
    ratioValue: 1.0,
    priority: 0,
    active: true
  };
  
  configJson: string = ''; // String representation of config JSON for editing
  
  isEditMode: boolean = false;
  loading: boolean = false;
  error: string | null = null;
  saving: boolean = false;

  ratioTypes = ['PERCENTAGE', 'MULTIPLIER', 'FIXED_QUANTITY'];

  constructor(
    private copyRuleService: CopyRuleService,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id && id !== 'new') {
      this.isEditMode = true;
      this.loadCopyRule(parseInt(id, 10));
    }
  }

  loadCopyRule(id: number) {
    this.loading = true;
    this.error = null;
    
    this.copyRuleService.getCopyRuleById(id).subscribe({
      next: (rule) => {
        this.rule = rule;
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Error loading copy rule: ' + (err.message || 'Unknown error');
        this.loading = false;
      }
    });
  }

  save() {
    if (!this.validateForm()) {
      return;
    }

    this.saving = true;
    this.error = null;

    // Parse config JSON
    let config: { [key: string]: any } | undefined;
    if (this.configJson && this.configJson.trim()) {
      try {
        config = JSON.parse(this.configJson);
      } catch (e) {
        this.error = 'Invalid JSON in config field: ' + (e instanceof Error ? e.message : 'Unknown error');
        this.saving = false;
        return;
      }
    }

    if (this.isEditMode) {
      const updateRequest: UpdateCopyRuleRequest = {
        ratioType: this.rule.ratioType,
        ratioValue: this.rule.ratioValue,
        orderTypes: this.rule.orderTypes,
        copyRoute: this.rule.copyRoute,
        locateRoute: this.rule.locateRoute,
        copyBroker: this.rule.copyBroker,
        minQuantity: this.rule.minQuantity,
        maxQuantity: this.rule.maxQuantity,
        priority: this.rule.priority,
        active: this.rule.active,
        description: this.rule.description,
        config: config
      };

      this.copyRuleService.updateCopyRule(this.rule.id!, updateRequest).subscribe({
        next: () => {
          this.router.navigate(['/copy-rules']);
        },
        error: (err) => {
          this.error = 'Error updating copy rule: ' + (err.message || 'Unknown error');
          this.saving = false;
        }
      });
    } else {
      const createRequest: CreateCopyRuleRequest = {
        primaryAccountNumber: this.rule.primaryAccountNumber || '',
        shadowAccountNumber: this.rule.shadowAccountNumber || '',
        ratioType: this.rule.ratioType!,
        ratioValue: this.rule.ratioValue!,
        orderTypes: this.rule.orderTypes,
        copyRoute: this.rule.copyRoute,
        locateRoute: this.rule.locateRoute,
        copyBroker: this.rule.copyBroker,
        minQuantity: this.rule.minQuantity,
        maxQuantity: this.rule.maxQuantity,
        priority: this.rule.priority,
        active: this.rule.active,
        description: this.rule.description,
        config: config
      };

      this.copyRuleService.createCopyRule(createRequest).subscribe({
        next: () => {
          this.router.navigate(['/copy-rules']);
        },
        error: (err) => {
          this.error = 'Error creating copy rule: ' + (err.message || 'Unknown error');
          this.saving = false;
        }
      });
    }
  }

  cancel() {
    this.router.navigate(['/copy-rules']);
  }

  validateForm(): boolean {
    if (!this.isEditMode) {
      if (!this.rule.primaryAccountNumber || this.rule.primaryAccountNumber.trim() === '') {
        this.error = 'Primary Account Number is required';
        return false;
      }
      if (!this.rule.shadowAccountNumber || this.rule.shadowAccountNumber.trim() === '') {
        this.error = 'Shadow Account Number is required';
        return false;
      }
    }
    if (!this.rule.ratioType) {
      this.error = 'Ratio Type is required';
      return false;
    }
    if (!this.rule.ratioValue || this.rule.ratioValue <= 0) {
      this.error = 'Ratio Value must be greater than 0';
      return false;
    }
    return true;
  }
}

