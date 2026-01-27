import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface CopyRule {
  id?: number;
  primaryAccountId: number;
  primaryAccountNumber?: string;
  shadowAccountId: number;
  shadowAccountNumber?: string;
  ratioType: 'PERCENTAGE' | 'MULTIPLIER' | 'FIXED_QUANTITY';
  ratioValue: number;
  orderTypes?: string;
  copyRoute?: string;
  locateRoute?: string;
  copyBroker?: string;
  minQuantity?: number;
  maxQuantity?: number;
  priority: number;
  active: boolean;
  description?: string;
  config?: { [key: string]: any };
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateCopyRuleRequest {
  primaryAccountNumber: string;
  shadowAccountNumber: string;
  ratioType: 'PERCENTAGE' | 'MULTIPLIER' | 'FIXED_QUANTITY';
  ratioValue: number;
  orderTypes?: string;
  copyRoute?: string;
  locateRoute?: string;
  copyBroker?: string;
  minQuantity?: number;
  maxQuantity?: number;
  priority?: number;
  active?: boolean;
  description?: string;
  config?: { [key: string]: any };
}

export interface UpdateCopyRuleRequest {
  ratioType?: 'PERCENTAGE' | 'MULTIPLIER' | 'FIXED_QUANTITY';
  ratioValue?: number;
  orderTypes?: string;
  copyRoute?: string;
  locateRoute?: string;
  copyBroker?: string;
  minQuantity?: number;
  maxQuantity?: number;
  priority?: number;
  active?: boolean;
  description?: string;
  config?: { [key: string]: any };
}

@Injectable({
  providedIn: 'root'
})
export class CopyRuleService {
  private apiUrl = '/api/copy-rules';

  constructor(private http: HttpClient) {}

  getAllCopyRules(
    primaryAccountId?: number,
    shadowAccountId?: number,
    active?: boolean
  ): Observable<CopyRule[]> {
    let params = new HttpParams();
    if (primaryAccountId !== undefined) {
      params = params.set('primaryAccountId', primaryAccountId.toString());
    }
    if (shadowAccountId !== undefined) {
      params = params.set('shadowAccountId', shadowAccountId.toString());
    }
    if (active !== undefined) {
      params = params.set('active', active.toString());
    }

    return this.http.get<CopyRule[]>(this.apiUrl, { params });
  }

  getCopyRuleById(id: number): Observable<CopyRule> {
    return this.http.get<CopyRule>(`${this.apiUrl}/${id}`);
  }

  createCopyRule(request: CreateCopyRuleRequest): Observable<CopyRule> {
    return this.http.post<CopyRule>(this.apiUrl, request);
  }

  updateCopyRule(id: number, request: UpdateCopyRuleRequest): Observable<CopyRule> {
    return this.http.put<CopyRule>(`${this.apiUrl}/${id}`, request);
  }

  deleteCopyRule(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}

