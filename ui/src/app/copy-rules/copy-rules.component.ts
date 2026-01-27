import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { CopyRuleService, CopyRule } from '../copy-rule.service';

@Component({
  selector: 'app-copy-rules',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './copy-rules.component.html',
  styleUrl: './copy-rules.component.scss'
})
export class CopyRulesComponent implements OnInit {
  copyRules: CopyRule[] = [];
  loading: boolean = false;
  error: string | null = null;
  showActiveOnly: boolean = true;

  constructor(private copyRuleService: CopyRuleService) {}

  ngOnInit() {
    this.loadCopyRules();
  }

  loadCopyRules() {
    this.loading = true;
    this.error = null;
    
    this.copyRuleService.getAllCopyRules(undefined, undefined, this.showActiveOnly ? true : undefined)
      .subscribe({
        next: (rules) => {
          this.copyRules = rules;
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Error loading copy rules: ' + (err.message || 'Unknown error');
          this.loading = false;
        }
      });
  }

  toggleActiveFilter() {
    this.showActiveOnly = !this.showActiveOnly;
    this.loadCopyRules();
  }

  onRefresh() {
    this.loadCopyRules();
  }

  deleteCopyRule(id: number) {
    if (!confirm('Are you sure you want to delete this copy rule?')) {
      return;
    }

    this.copyRuleService.deleteCopyRule(id).subscribe({
      next: () => {
        this.loadCopyRules();
      },
      error: (err) => {
        this.error = 'Error deleting copy rule: ' + (err.message || 'Unknown error');
      }
    });
  }

  toggleActive(rule: CopyRule) {
    const update = { active: !rule.active };
    this.copyRuleService.updateCopyRule(rule.id!, update).subscribe({
      next: () => {
        this.loadCopyRules();
      },
      error: (err) => {
        this.error = 'Error updating copy rule: ' + (err.message || 'Unknown error');
      }
    });
  }
}

