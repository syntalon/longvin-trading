import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface Order {
  id?: string;
  accountId?: number;
  accountNumber?: string;
  accountType?: 'PRIMARY' | 'SHADOW';
  primaryOrderClOrdId?: string;
  fixOrderId?: string;
  fixClOrdId?: string;
  fixOrigClOrdId?: string;
  symbol?: string;
  side?: string;
  ordType?: string;
  timeInForce?: string;
  orderQty?: number;
  price?: number;
  stopPx?: number;
  exDestination?: string;
  execType?: string;
  ordStatus?: string;
  cumQty?: number;
  leavesQty?: number;
  avgPx?: number;
  lastPx?: number;
  lastQty?: number;
  createdAt?: string;
  updatedAt?: string;
  isCopyOrder?: boolean;
  isLocateOrder?: boolean;
  eventCount?: number;
}

export interface OrderSearchParams {
  accountNumber?: string;
  accountId?: number;
  symbol?: string;
  fixClOrdId?: string;
  fixOrderId?: string;
  ordStatus?: string;
  execType?: string;
  isCopyOrder?: boolean;
  startDate?: string;
  endDate?: string;
  page?: number;
  size?: number;
}

@Injectable({
  providedIn: 'root'
})
export class OrderService {
  private apiUrl = '/api/orders';

  constructor(private http: HttpClient) {}

  searchOrders(params: OrderSearchParams): Observable<Order[]> {
    let httpParams = new HttpParams();
    
    if (params.accountNumber) {
      httpParams = httpParams.set('accountNumber', params.accountNumber);
    }
    if (params.accountId) {
      httpParams = httpParams.set('accountId', params.accountId.toString());
    }
    if (params.symbol) {
      httpParams = httpParams.set('symbol', params.symbol);
    }
    if (params.fixClOrdId) {
      httpParams = httpParams.set('fixClOrdId', params.fixClOrdId);
    }
    if (params.fixOrderId) {
      httpParams = httpParams.set('fixOrderId', params.fixOrderId);
    }
    if (params.ordStatus) {
      httpParams = httpParams.set('ordStatus', params.ordStatus);
    }
    if (params.execType) {
      httpParams = httpParams.set('execType', params.execType);
    }
    if (params.isCopyOrder !== undefined) {
      httpParams = httpParams.set('isCopyOrder', params.isCopyOrder.toString());
    }
    if (params.startDate) {
      httpParams = httpParams.set('startDate', params.startDate);
    }
    if (params.endDate) {
      httpParams = httpParams.set('endDate', params.endDate);
    }
    if (params.page !== undefined) {
      httpParams = httpParams.set('page', params.page.toString());
    }
    if (params.size !== undefined) {
      httpParams = httpParams.set('size', params.size.toString());
    }
    
    return this.http.get<Order[]>(this.apiUrl, { params: httpParams });
  }

  getOrderById(id: string): Observable<Order> {
    return this.http.get<Order>(`${this.apiUrl}/${id}`);
  }

  getOrdersByPrimaryClOrdId(primaryClOrdId: string): Observable<Order[]> {
    return this.http.get<Order[]>(`${this.apiUrl}/by-primary/${primaryClOrdId}`);
  }

  getOrderEvents(orderId?: string, fixClOrdId?: string): Observable<OrderEvent[]> {
    let httpParams = new HttpParams();
    if (orderId) {
      httpParams = httpParams.set('orderId', orderId);
    }
    if (fixClOrdId) {
      httpParams = httpParams.set('fixClOrdId', fixClOrdId);
    }
    return this.http.get<OrderEvent[]>(`${this.apiUrl}/events`, { params: httpParams });
  }
}

export interface OrderEvent {
  id?: string;
  orderId?: string;
  fixExecId?: string;
  execType?: string;
  ordStatus?: string;
  fixOrderId?: string;
  fixClOrdId?: string;
  fixOrigClOrdId?: string;
  symbol?: string;
  side?: string;
  ordType?: string;
  timeInForce?: string;
  orderQty?: number;
  price?: number;
  stopPx?: number;
  lastPx?: number;
  lastQty?: number;
  cumQty?: number;
  leavesQty?: number;
  avgPx?: number;
  account?: string;
  transactTime?: string;
  text?: string;
  eventTime?: string;
  rawFixMessage?: string;
  sessionId?: string;
}

